package net.runelite.client;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Every net.runelite method/field the injected game client calls must exist in this fork's
 * runelite-api/client. The injected client is a -SNAPSHOT Gradle re-downloads whenever upstream
 * publishes one, so it can move to a newer API than this fork has merged - the client then starts
 * fine and dies at login with a NoSuchMethodError (2026-09-26: rev 241's
 * VarbitChanged.setLongValue(long), before upstream's "api: update 241" was merged).
 * Fix a failure by merging upstream/master, not by editing the API by hand.
 */
public class InjectedClientApiTest
{
	private static final int FIELDREF = 9;
	private static final int METHODREF = 10;
	private static final int INTERFACE_METHODREF = 11;

	@Test
	public void injectedClientOnlyUsesApiThatExists() throws Exception
	{
		ClassLoader loader = getClass().getClassLoader();
		URL clientClass = loader.getResource("client.class");
		assertNotNull("injected-client isn't on the test classpath", clientClass);

		Set<String> missing = new TreeSet<>();
		try (JarFile jar = ((JarURLConnection) clientClass.openConnection()).getJarFile())
		{
			Set<String> ownClasses = new HashSet<>();
			for (Enumeration<JarEntry> e = jar.entries(); e.hasMoreElements(); )
			{
				String name = e.nextElement().getName();
				if (name.endsWith(".class"))
				{
					ownClasses.add(name.substring(0, name.length() - ".class".length()));
				}
			}

			for (Enumeration<JarEntry> e = jar.entries(); e.hasMoreElements(); )
			{
				JarEntry entry = e.nextElement();
				if (!entry.getName().endsWith(".class"))
				{
					continue;
				}
				try (InputStream in = jar.getInputStream(entry))
				{
					for (String[] ref : memberRefs(in))
					{
						if (ref[1].startsWith("net/runelite/") && !ownClasses.contains(ref[1]) && !resolves(loader, ref))
						{
							missing.add(ref[1].replace('/', '.') + "." + ref[2] + " " + ref[3]);
						}
					}
				}
			}
		}

		assertTrue("The injected client uses API this fork doesn't have - merge upstream/master:\n  "
			+ String.join("\n  ", missing), missing.isEmpty());
	}

	/**
	 * The field and method references in a class file's constant pool, as {kind, owner, name,
	 * descriptor}.
	 */
	private static List<String[]> memberRefs(InputStream stream) throws IOException
	{
		DataInputStream in = new DataInputStream(stream);
		in.readInt(); // magic
		in.readUnsignedShort(); // minor
		in.readUnsignedShort(); // major
		int count = in.readUnsignedShort();

		int[] tags = new int[count];
		int[] first = new int[count];
		int[] second = new int[count];
		String[] utf8 = new String[count];
		for (int i = 1; i < count; i++)
		{
			int tag = in.readUnsignedByte();
			tags[i] = tag;
			switch (tag)
			{
				case 1: // Utf8
					utf8[i] = in.readUTF();
					break;
				case 7: // Class
				case 8: // String
				case 16: // MethodType
				case 19: // Module
				case 20: // Package
					first[i] = in.readUnsignedShort();
					break;
				case 15: // MethodHandle
					in.readUnsignedByte();
					in.readUnsignedShort();
					break;
				case 3: // Integer
				case 4: // Float
				case FIELDREF:
				case METHODREF:
				case INTERFACE_METHODREF:
				case 12: // NameAndType
				case 17: // Dynamic
				case 18: // InvokeDynamic
					first[i] = in.readUnsignedShort();
					second[i] = in.readUnsignedShort();
					break;
				case 5: // Long
				case 6: // Double
					in.readLong();
					i++; // takes two slots
					break;
				default:
					throw new IOException("unknown constant pool tag " + tag);
			}
		}

		List<String[]> refs = new ArrayList<>();
		for (int i = 1; i < count; i++)
		{
			if (tags[i] == FIELDREF || tags[i] == METHODREF || tags[i] == INTERFACE_METHODREF)
			{
				String owner = utf8[first[first[i]]];
				int nameAndType = second[i];
				refs.add(new String[]{tags[i] == FIELDREF ? "field" : "method", owner,
					utf8[first[nameAndType]], utf8[second[nameAndType]]});
			}
		}
		return refs;
	}

	private static boolean resolves(ClassLoader loader, String[] ref)
	{
		Class<?> owner;
		try
		{
			owner = Class.forName(ref[1].replace('/', '.'), false, loader);
		}
		catch (ClassNotFoundException | LinkageError e)
		{
			return false;
		}
		return "field".equals(ref[0]) ? hasField(owner, ref[2], ref[3]) : hasMethod(owner, ref[2], ref[3]);
	}

	/**
	 * Searches the class, its superclasses and every interface - where the JVM would look.
	 */
	private static boolean hasMethod(Class<?> type, String name, String descriptor)
	{
		if (type == null)
		{
			return false;
		}
		List<Executable> candidates = new ArrayList<>();
		if ("<init>".equals(name))
		{
			for (Constructor<?> c : type.getDeclaredConstructors())
			{
				candidates.add(c);
			}
		}
		else
		{
			for (Method m : type.getDeclaredMethods())
			{
				if (m.getName().equals(name))
				{
					candidates.add(m);
				}
			}
		}
		for (Executable candidate : candidates)
		{
			if (descriptor(candidate).equals(descriptor))
			{
				return true;
			}
		}
		if (hasMethod(type.getSuperclass(), name, descriptor))
		{
			return true;
		}
		for (Class<?> iface : type.getInterfaces())
		{
			if (hasMethod(iface, name, descriptor))
			{
				return true;
			}
		}
		return false;
	}

	private static boolean hasField(Class<?> type, String name, String descriptor)
	{
		if (type == null)
		{
			return false;
		}
		for (Field f : type.getDeclaredFields())
		{
			if (f.getName().equals(name) && descriptor(f.getType()).equals(descriptor))
			{
				return true;
			}
		}
		for (Class<?> iface : type.getInterfaces())
		{
			if (hasField(iface, name, descriptor))
			{
				return true;
			}
		}
		return hasField(type.getSuperclass(), name, descriptor);
	}

	private static String descriptor(Executable executable)
	{
		StringBuilder sb = new StringBuilder("(");
		for (Class<?> param : executable.getParameterTypes())
		{
			sb.append(descriptor(param));
		}
		sb.append(')');
		sb.append(executable instanceof Method ? descriptor(((Method) executable).getReturnType()) : "V");
		return sb.toString();
	}

	private static String descriptor(Class<?> type)
	{
		if (type.isArray())
		{
			return type.getName().replace('.', '/');
		}
		if (type.isPrimitive())
		{
			switch (type.getName())
			{
				case "void": return "V";
				case "boolean": return "Z";
				case "byte": return "B";
				case "char": return "C";
				case "short": return "S";
				case "int": return "I";
				case "long": return "J";
				case "float": return "F";
				case "double": return "D";
				default: throw new IllegalArgumentException(type.getName());
			}
		}
		return "L" + type.getName().replace('.', '/') + ";";
	}
}
