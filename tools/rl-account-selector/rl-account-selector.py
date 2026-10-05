#!/usr/bin/env python3
"""RL Account Selector - pick a Jagex account and launch/rebuild this RuneLite fork.

Accounts are credentials files from the Jagex Launcher's --insecure-write-credentials dump
(https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts), kept in ~/.runelite/Accounts.

How the client uses them (read from the injected client, not documented on the wiki):
  - it reads new File(~/.runelite, System.getProperty("runelite.credentials.path",
    "credentials.properties")), keys JX_ACCESS_TOKEN, JX_REFRESH_TOKEN, JX_CHARACTER_ID,
    JX_SESSION_ID, JX_DISPLAY_NAME;
  - when it refreshes the tokens it writes the new ones back to that same file.

So the default here is to point the client at the account's own file (-Drunelite.credentials.path)
- refreshed tokens land in Accounts/, and several accounts can run at once. "Copy mode" does the
original copy-to-credentials.properties instead, and copies the file back to its account before
switching away, so refreshed tokens aren't lost.
"""

import json
import os
import re
import shutil
import signal
import subprocess
import sys
import threading

import gi

gi.require_version("Gtk", "3.0")
from gi.repository import GLib, Gtk, Pango  # noqa: E402

APP_NAME = "RL Account Selector"
RUNELITE_DIR = os.path.expanduser("~/.runelite")
ACCOUNTS_DIR = os.path.join(RUNELITE_DIR, "Accounts")
ROOT_CREDENTIALS = os.path.join(RUNELITE_DIR, "credentials.properties")
CLIENT_LOG = os.path.join(RUNELITE_DIR, "logs", "rl-account-selector-client.log")
CONFIG_HOME = os.environ.get("XDG_CONFIG_HOME", os.path.expanduser("~/.config"))
STATE_FILE = os.path.join(CONFIG_HOME, "rl-account-selector", "state.json")
OLD_STATE_FILE = os.path.join(CONFIG_HOME, "runelite-accounts", "state.json")  # before the rename
# This file lives at <repo>/tools/rl-account-selector/ - resolve through the ~/.local/bin symlink.
REPO_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.realpath(__file__))))
RUN_SCRIPT = os.path.join(REPO_DIR, "run-runelite.sh")
CREDENTIALS_PROPERTY = "-Drunelite.credentials.path="
# For "No account": a file in a folder that doesn't exist, so there's nothing to read, and the
# client can't create it either (it only writes after loading credentials, but be sure).
NO_CREDENTIALS_PATH = "no-credentials/none.properties"
ICON = os.path.join(os.path.dirname(os.path.realpath(__file__)), "icon.png")
APP_ID = "dev.jackhaynes.RLAccountSelector"
CLIENT_JAR = re.compile(r"java .*-jar \S*client-\S*-shaded\.jar")


# ---------------------------------------------------------------------------------------------
# Credentials files

def read_properties(path):
    """Minimal java.util.Properties reader - enough for what Properties.store() writes."""
    props = {}
    try:
        with open(path, encoding="utf-8") as f:
            lines = f.read().splitlines()
    except OSError:
        return props
    for line in lines:
        line = line.strip()
        if not line or line[0] in "#!":
            continue
        m = re.match(r"((?:[^=:\s\\]|\\.)+)\s*[=:\s]\s*(.*)", line)
        if m:
            props[_unescape(m.group(1))] = _unescape(m.group(2))
    return props


def _unescape(s):
    def repl(m):
        c = m.group(1)
        if c.startswith("u"):
            return chr(int(c[1:], 16))
        return {"t": "\t", "n": "\n", "r": "\r", "f": "\f"}.get(c, c)
    return re.sub(r"\\(u[0-9a-fA-F]{4}|.)", repl, s)


class Account:
    def __init__(self, path):
        self.path = path
        self.file_name = os.path.basename(path)
        self.name = self.file_name[:-len(".properties")]
        props = read_properties(path)
        self.display_name = props.get("JX_DISPLAY_NAME") or ""
        self.character_id = props.get("JX_CHARACTER_ID") or ""
        self.valid = bool(props.get("JX_SESSION_ID") and self.character_id)

    @property
    def credentials_path(self):
        """Path relative to ~/.runelite, as the client resolves runelite.credentials.path."""
        return os.path.relpath(self.path, RUNELITE_DIR)


class NoAccount:
    """The "No account" row: the client's normal login screen."""
    path = None
    file_name = ""
    name = ""
    display_name = "No account"
    character_id = ""
    valid = True
    credentials_path = NO_CREDENTIALS_PATH


def list_accounts():
    try:
        names = sorted(n for n in os.listdir(ACCOUNTS_DIR) if n.endswith(".properties"))
    except FileNotFoundError:
        return []
    return [Account(os.path.join(ACCOUNTS_DIR, n)) for n in names]


def copy_private(src, dst):
    """Copy a credentials file, readable by you only."""
    tmp = dst + ".tmp"
    shutil.copyfile(src, tmp)
    os.chmod(tmp, 0o600)
    os.replace(tmp, dst)


def safe_file_name(name):
    name = re.sub(r"[^A-Za-z0-9_.-]+", "_", name.strip()).strip("._")
    return name or "account"


# ---------------------------------------------------------------------------------------------
# Running clients

def running_clients():
    """{pid: credentials path or None} for every dev client of this fork that's running."""
    clients = {}
    try:
        out = subprocess.run(["ps", "-eo", "pid=,args="], capture_output=True, text=True).stdout
    except OSError:
        return clients
    for line in out.splitlines():
        pid, _, args = line.strip().partition(" ")
        if not CLIENT_JAR.search(args):
            continue
        clients[int(pid)] = _credentials_path_of(pid)
    return clients


def _credentials_path_of(pid):
    try:
        with open(f"/proc/{pid}/environ", "rb") as f:
            env = f.read().split(b"\0")
    except OSError:
        return None
    for var in env:
        if var.startswith(b"JDK_JAVA_OPTIONS="):
            m = re.search(re.escape(CREDENTIALS_PROPERTY) + r'([^"]+?\.properties)',
                          var.decode(errors="replace"))
            if m:
                return m.group(1)
    return None


# ---------------------------------------------------------------------------------------------
# State (selected account, mode, which account credentials.properties came from)

def load_state():
    try:
        path = STATE_FILE if os.path.exists(STATE_FILE) else OLD_STATE_FILE
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def save_state(state):
    os.makedirs(os.path.dirname(STATE_FILE), exist_ok=True)
    with open(STATE_FILE, "w", encoding="utf-8") as f:
        json.dump(state, f, indent=2)


# ---------------------------------------------------------------------------------------------
# UI

class AccountRow(Gtk.ListBoxRow):
    def __init__(self, account):
        super().__init__()
        self.account = account
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2, margin=8)
        self.title = Gtk.Label(xalign=0)
        self.subtitle = Gtk.Label(xalign=0)
        self.subtitle.get_style_context().add_class("dim-label")
        box.pack_start(self.title, False, False, 0)
        box.pack_start(self.subtitle, False, False, 0)
        self.add(box)

    def update(self, running, active):
        a = self.account
        title = GLib.markup_escape_text(a.display_name or a.name)
        self.title.set_markup(f"<b>{title}</b>")
        if isinstance(a, NoAccount):
            parts = ["Login screen - type your login, no saved credentials"]
        else:
            parts = [a.file_name]
        if not a.valid:
            parts.append("not a Jagex credentials file")
        if running:
            parts.append("● running")
        if active:
            parts.append("in credentials.properties")
        self.subtitle.set_text("  ·  ".join(parts))


class LauncherWindow(Gtk.ApplicationWindow):
    def __init__(self, app):
        super().__init__(application=app, title=APP_NAME)
        self.set_default_size(560, 620)
        self.state = load_state()
        self.build_proc = None
        self.running_count = 0
        self.rows = []

        header = Gtk.HeaderBar(show_close_button=True, title=APP_NAME, subtitle=REPO_DIR)
        self.set_titlebar(header)
        refresh = Gtk.Button.new_from_icon_name("view-refresh-symbolic", Gtk.IconSize.BUTTON)
        refresh.set_tooltip_text("Re-read the Accounts folder")
        refresh.connect("clicked", lambda _b: self.reload_accounts())
        header.pack_end(refresh)
        folder = Gtk.Button.new_from_icon_name("folder-open-symbolic", Gtk.IconSize.BUTTON)
        folder.set_tooltip_text("Open the Accounts folder")
        folder.connect("clicked", self.on_open_folder)
        header.pack_end(folder)

        outer = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10, margin=12)
        self.add(outer)

        # Accounts
        self.list = Gtk.ListBox()
        self.list.set_selection_mode(Gtk.SelectionMode.SINGLE)
        self.list.connect("row-selected", self.on_row_selected)
        scroll = Gtk.ScrolledWindow(min_content_height=170)
        scroll.set_shadow_type(Gtk.ShadowType.IN)
        scroll.add(self.list)
        outer.pack_start(scroll, True, True, 0)

        # Actions
        actions = Gtk.Box(spacing=6)
        actions.set_homogeneous(True)
        self.play_btn = Gtk.Button(label="Play")
        self.play_btn.get_style_context().add_class("suggested-action")
        self.play_btn.connect("clicked", self.on_play)
        self.rebuild_play_btn = Gtk.Button(label="Rebuild & Play")
        self.rebuild_play_btn.connect("clicked", self.on_rebuild_and_play)
        self.rebuild_btn = Gtk.Button(label="Rebuild")
        self.rebuild_btn.connect("clicked", self.on_rebuild)
        for b in (self.play_btn, self.rebuild_play_btn, self.rebuild_btn):
            actions.pack_start(b, True, True, 0)
        outer.pack_start(actions, False, False, 0)

        more = Gtk.Box(spacing=6)
        import_btn = Gtk.Button(label="Import credentials.properties…")
        import_btn.set_tooltip_text(
            "Save ~/.runelite/credentials.properties (fresh from the Jagex Launcher's "
            "--insecure-write-credentials) as an account, or update the existing one")
        import_btn.connect("clicked", self.on_import)
        more.pack_start(import_btn, False, False, 0)
        self.copy_mode = Gtk.CheckButton(label="Copy mode")
        self.copy_mode.set_tooltip_text(
            "Copy the account to ~/.runelite/credentials.properties before launching (one "
            "account at a time) instead of pointing the client at the account's own file")
        self.copy_mode.set_active(bool(self.state.get("copy_mode")))
        self.copy_mode.connect("toggled", self.on_copy_mode_toggled)
        more.pack_end(self.copy_mode, False, False, 0)
        outer.pack_start(more, False, False, 0)

        # Status + build output
        self.status = Gtk.Label(xalign=0, wrap=True)
        self.status.set_line_wrap_mode(Pango.WrapMode.WORD_CHAR)
        outer.pack_start(self.status, False, False, 0)
        self.output = Gtk.TextView(editable=False, cursor_visible=False, monospace=True)
        self.output.set_wrap_mode(Gtk.WrapMode.CHAR)
        out_scroll = Gtk.ScrolledWindow(min_content_height=160)
        out_scroll.set_shadow_type(Gtk.ShadowType.IN)
        out_scroll.add(self.output)
        self.expander = Gtk.Expander(label="Build output")
        self.expander.add(out_scroll)
        outer.pack_start(self.expander, False, False, 0)
        self.expander.connect("notify::expanded", lambda e, _p: e.get_parent().child_set_property(
            e, "expand", e.get_expanded()))

        self.reload_accounts()
        GLib.timeout_add_seconds(2, self.refresh_running)

    # -- accounts list --------------------------------------------------------------------

    def reload_accounts(self, select=None):
        select = select or (self.selected().name if self.selected() else self.state.get("selected"))
        for row in self.rows:
            self.list.remove(row)
        self.rows = [AccountRow(a) for a in [NoAccount()] + list_accounts()]
        for row in self.rows:
            self.list.add(row)
            if row.account.name == select:
                self.list.select_row(row)
        if not self.list.get_selected_row():
            self.list.select_row(self.rows[min(1, len(self.rows) - 1)])
        self.list.show_all()
        self.refresh_running()

    def selected(self):
        row = self.list.get_selected_row()
        return row.account if row else None

    def on_row_selected(self, _list, row):
        if row:
            self.state["selected"] = row.account.name
            save_state(self.state)
        self.update_buttons()

    def refresh_running(self):
        clients = running_clients()
        running_paths = set(p for p in clients.values() if p)
        active = self.state.get("copied_from") if os.path.exists(ROOT_CREDENTIALS) else None
        for row in self.rows:
            row.update(row.account.credentials_path in running_paths, row.account.name == active)
        self.running_count = len(clients)
        self.update_buttons()
        return True

    def update_buttons(self):
        building = self.build_proc is not None
        has_account = self.selected() is not None
        self.play_btn.set_sensitive(has_account and not building)
        self.rebuild_play_btn.set_sensitive(has_account and not building)
        self.rebuild_btn.set_label("Cancel build" if building else "Rebuild")
        self.rebuild_btn.get_style_context().remove_class("destructive-action")
        if building:
            self.rebuild_btn.get_style_context().add_class("destructive-action")

    def set_status(self, text):
        self.status.set_text(text)

    # -- play -----------------------------------------------------------------------------

    def on_play(self, _button):
        account = self.selected()
        if account is None or self.build_proc is not None:
            return
        if not account.valid and not self.confirm(
                f"{account.file_name} doesn't look like a Jagex credentials file "
                "(no JX_SESSION_ID / JX_CHARACTER_ID). Launch anyway?"):
            return
        if self.copy_mode.get_active() and not isinstance(account, NoAccount):
            if not self.activate_by_copy(account):
                return
            env_opts = None
        else:
            env_opts = f'"{CREDENTIALS_PROPERTY}{account.credentials_path}"'
        self.launch(account, env_opts)

    def activate_by_copy(self, account):
        """Copy mode: put the account into credentials.properties, saving the previous one back."""
        if self.running_count and not self.confirm(
                "A client is already running. In copy mode it keeps writing refreshed tokens to "
                "credentials.properties, which would now belong to another account.\n\n"
                "Close it first (or turn copy mode off to run accounts side by side). "
                "Launch anyway?"):
            return False
        if os.path.exists(ROOT_CREDENTIALS):
            current = read_properties(ROOT_CREDENTIALS)
            char_id = current.get("JX_CHARACTER_ID")
            owner = next((a for a in list_accounts() if char_id and a.character_id == char_id), None)
            if owner is None and current:
                # Unknown account (e.g. a fresh launcher dump) - don't silently overwrite it.
                if not self.confirm(
                        "~/.runelite/credentials.properties holds an account that isn't in the "
                        f"Accounts folder ({current.get('JX_DISPLAY_NAME') or 'no display name'})."
                        "\n\nOverwrite it? (Cancel, then use Import to keep it.)"):
                    return False
            elif owner is not None:
                # It may hold tokens the client refreshed since it was copied - keep them.
                copy_private(ROOT_CREDENTIALS, owner.path)
        copy_private(account.path, ROOT_CREDENTIALS)
        self.state["copied_from"] = account.name
        save_state(self.state)
        return True

    def launch(self, account, env_opts):
        if not self.newest_jar():
            self.set_status("No client jar yet - use Rebuild & Play.")
            return
        env = dict(os.environ)
        if env_opts:
            env["JDK_JAVA_OPTIONS"] = env_opts
        else:
            env.pop("JDK_JAVA_OPTIONS", None)
        for key in [k for k in env if k.startswith("JX_")]:
            del env[key]  # the client also takes credentials from JX_* environment variables
        os.makedirs(os.path.dirname(CLIENT_LOG), exist_ok=True)
        log = open(CLIENT_LOG, "ab")
        log.write(f"\n=== launching {account.name or 'no account'} ===\n".encode())
        log.flush()
        # Own session + log file: the client outlives this window and never writes to a closed pipe.
        subprocess.Popen([RUN_SCRIPT], cwd=REPO_DIR, env=env, stdin=subprocess.DEVNULL,
                         stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        log.close()
        self.set_status(f"Launched {account.display_name or account.name}. "
                        f"Client output: {CLIENT_LOG}")
        GLib.timeout_add(1500, lambda: self.refresh_running() and False)

    @staticmethod
    def newest_jar():
        libs = os.path.join(REPO_DIR, "runelite-client", "build", "libs")
        try:
            jars = [os.path.join(libs, n) for n in os.listdir(libs)
                    if n.startswith("client-") and n.endswith("-shaded.jar")]
        except FileNotFoundError:
            return None
        return max(jars, key=os.path.getmtime) if jars else None

    # -- build ----------------------------------------------------------------------------

    def on_rebuild(self, _button):
        if self.build_proc is not None:
            self.cancel_build()
        else:
            self.start_build(then_play=False)

    def on_rebuild_and_play(self, _button):
        self.start_build(then_play=True)

    def start_build(self, then_play):
        if self.build_proc is not None:
            return
        if self.running_count and not self.confirm(
                f"{self.running_count} client(s) running. Rebuilding replaces the jar a running "
                "client is still loading classes from, which can crash or hang it.\n\n"
                "Close the client(s) first. Rebuild anyway?"):
            return
        account = self.selected()
        buf = self.output.get_buffer()
        buf.set_text("$ ./gradlew :client:shadowJar\n")
        self.expander.set_expanded(True)
        self.set_status("Building…")
        self.build_proc = subprocess.Popen(
            ["./gradlew", ":client:shadowJar"], cwd=REPO_DIR, stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=True)
        self.update_buttons()
        threading.Thread(target=self.pump_build, args=(self.build_proc, then_play, account),
                         daemon=True).start()

    def pump_build(self, proc, then_play, account):
        for raw in iter(proc.stdout.readline, b""):
            GLib.idle_add(self.append_output, raw.decode(errors="replace"))
        code = proc.wait()
        GLib.idle_add(self.build_finished, proc, code, then_play, account)

    def append_output(self, text):
        buf = self.output.get_buffer()
        buf.insert(buf.get_end_iter(), text)
        self.output.scroll_to_mark(buf.get_insert(), 0, False, 0, 1)
        buf.place_cursor(buf.get_end_iter())
        return False

    def build_finished(self, proc, code, then_play, account):
        cancelled = getattr(proc, "cancelled", False)
        self.build_proc = None
        self.update_buttons()
        if cancelled:
            self.set_status("Build cancelled.")
        elif code != 0:
            self.set_status(f"Build failed (exit {code}) - see the output.")
        else:
            self.set_status("Build succeeded.")
            if then_play and account is not None:
                self.reload_accounts(select=account.name)
                self.on_play(None)
        return False

    def cancel_build(self):
        proc = self.build_proc
        if proc is None:
            return
        proc.cancelled = True
        try:
            os.killpg(proc.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass

    # -- misc -----------------------------------------------------------------------------

    def on_copy_mode_toggled(self, button):
        self.state["copy_mode"] = button.get_active()
        save_state(self.state)

    def on_open_folder(self, _button):
        os.makedirs(ACCOUNTS_DIR, exist_ok=True)
        subprocess.Popen(["xdg-open", ACCOUNTS_DIR], start_new_session=True)

    def on_import(self, _button):
        if not os.path.exists(ROOT_CREDENTIALS):
            self.message("There's no ~/.runelite/credentials.properties.\n\nLaunch the account "
                         "from the Jagex Launcher with --insecure-write-credentials first.")
            return
        props = read_properties(ROOT_CREDENTIALS)
        char_id = props.get("JX_CHARACTER_ID")
        if not (char_id and props.get("JX_SESSION_ID")):
            self.message("~/.runelite/credentials.properties isn't a Jagex credentials file.")
            return
        existing = next((a for a in list_accounts() if a.character_id == char_id), None)
        display = props.get("JX_DISPLAY_NAME") or ""
        if existing is not None:
            if not self.confirm(f"This is {existing.file_name}'s character"
                                f"{' (' + display + ')' if display else ''}.\n\n"
                                "Replace that account's file with these (newer) credentials?"):
                return
            target = existing
        else:
            name = self.ask_name(safe_file_name(display or "account"))
            if not name:
                return
            path = os.path.join(ACCOUNTS_DIR, safe_file_name(name) + ".properties")
            if os.path.exists(path) and not self.confirm(
                    f"{os.path.basename(path)} already exists (a different character). Overwrite it?"):
                return
            target = None
        os.makedirs(ACCOUNTS_DIR, exist_ok=True)
        dest = target.path if target else path
        copy_private(ROOT_CREDENTIALS, dest)
        if self.copy_mode.get_active():
            self.state["copied_from"] = os.path.basename(dest)[:-len(".properties")]
            save_state(self.state)
        self.reload_accounts(select=os.path.basename(dest)[:-len(".properties")])
        self.set_status(f"Saved as {os.path.basename(dest)}.")

    def ask_name(self, default):
        dialog = Gtk.MessageDialog(transient_for=self, modal=True,
                                   message_type=Gtk.MessageType.QUESTION,
                                   buttons=Gtk.ButtonsType.OK_CANCEL,
                                   text="Save these credentials as account:")
        entry = Gtk.Entry(text=default, activates_default=True)
        dialog.get_message_area().pack_start(entry, False, False, 0)
        dialog.set_default_response(Gtk.ResponseType.OK)
        dialog.show_all()
        ok = dialog.run() == Gtk.ResponseType.OK
        name = entry.get_text().strip()
        dialog.destroy()
        return name if ok else None

    def confirm(self, text):
        dialog = Gtk.MessageDialog(transient_for=self, modal=True,
                                   message_type=Gtk.MessageType.WARNING,
                                   buttons=Gtk.ButtonsType.OK_CANCEL, text=text)
        ok = dialog.run() == Gtk.ResponseType.OK
        dialog.destroy()
        return ok

    def message(self, text):
        dialog = Gtk.MessageDialog(transient_for=self, modal=True,
                                   message_type=Gtk.MessageType.INFO,
                                   buttons=Gtk.ButtonsType.OK, text=text)
        dialog.run()
        dialog.destroy()


class LauncherApp(Gtk.Application):
    def __init__(self):
        super().__init__(application_id=APP_ID)

    def do_activate(self):
        window = self.get_active_window() or LauncherWindow(self)
        window.show_all()
        window.present()


if __name__ == "__main__":
    GLib.set_prgname("rl-account-selector")  # X11 WM_CLASS, matched by the desktop entry
    if os.path.exists(ICON):
        Gtk.Window.set_default_icon_from_file(ICON)
    sys.exit(LauncherApp().run(sys.argv))
