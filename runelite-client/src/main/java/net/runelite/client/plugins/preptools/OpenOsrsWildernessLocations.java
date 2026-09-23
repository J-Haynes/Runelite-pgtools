/*******************************************************************************
 * Copyright (c) 2019 openosrs
 * Redistributions and modifications of this software are permitted as long as this notice remains in its original unmodified state at the top of this file.
 * If there are any questions comments, or feedback about this software, please direct all inquiries directly to the file authors:
 * ST0NEWALL#9112
 * openosrs Discord: https://discord.gg/Q7wFtCe
 * openosrs website: https://openosrs.com
 ******************************************************************************/

package net.runelite.client.plugins.preptools;

import java.util.ArrayList;
import java.util.List;

/**
 * The original OpenOSRS starter wilderness locations. The 53 the team kept are now part of the
 * shipped map (wilderness_locations.json, priority -1); this list is only used by
 * WildernessLocationStore's one-time migration, to recognise these entries in an old saved list as
 * stock data rather than a user's own work. Names and coordinates are the wilderness entries of
 * OpenOSRS's net.runelite.client.game.WorldLocation enum (archived fork JourneyDeprecated/OpenOSRS,
 * last changed January 2020) - its licence notice above is kept verbatim, as it requires.
 *
 * Converted from its Location(x, y, x1, y1) corner pairs (north-west and south-east corners) to
 * this plugin's inclusive min/max box. NOTE upstream itself passed the NORTH y as a WorldArea's
 * SOUTH-west origin, so its own lookups were shifted a whole box-height north; the corners as
 * written are what's clearly intended, so that's what's used here.
 *
 * Pre-dates Ferox Enclave (2020) and every wilderness change since - expect stale entries around
 * Ferox especially (e.g. "13s", the old Clan Wars portal). They were imported at
 * IMPORTED_PRIORITY, below hand-mapped boxes, so those always win where they overlap.
 */
final class OpenOsrsWildernessLocations
{
	static final int IMPORTED_PRIORITY = -1;

	private OpenOsrsWildernessLocations()
	{
	}

	static List<WildernessLocation> locations()
	{
		List<WildernessLocation> locations = new ArrayList<>();
		locations.add(new WildernessLocation("Axe Hut", 3187, 3957, 3194, 3962, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Bandit Camp", 3017, 3681, 3059, 3712, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Callisto", 3266, 3827, 3315, 3863, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Cemetery", 2956, 3736, 2996, 3767, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Chaos Fanatic", 2971, 3834, 2992, 3854, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Chaos Temple", 3220, 3593, 3255, 3632, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Chins", 3128, 3754, 3160, 3792, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Corp Cave", 3201, 3672, 3219, 3684, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Crazy Archaeologist", 2952, 3678, 2985, 3709, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Dark Warriors", 3014, 3616, 3046, 3648, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Dwarves", 3230, 3779, 3264, 3805, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("East Drags", 3326, 3671, 3365, 3704, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Ents", 3300, 3584, 3320, 3627, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("50 ports", 3301, 3909, 3315, 3923, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Fire Giant Entrance", 3042, 3920, 3051, 3929, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Gap", 3238, 3841, 3258, 3855, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Gdz", 3279, 3875, 3296, 3895, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("44s", 2973, 3859, 2987, 3870, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Glory Hill", 3331, 3866, 3348, 3890, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Glory Hole", 3352, 3869, 3386, 3897, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Graves", 3128, 3658, 3181, 3686, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Graveyard Drags", 3129, 3691, 3172, 3717, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("God Wars Dungeon", 3010, 3727, 3027, 3745, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("High Altar", 2945, 3813, 2970, 3826, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Hill Giants", 3282, 3674, 3300, 3687, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("35 Obelisk", 3097, 3785, 3115, 3804, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Hobgoblins", 3073, 3745, 3104, 3775, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Ice Gate", 2945, 3878, 2978, 3913, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Ice Rock", 2957, 3929, 2984, 3942, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("KBD CAGE", 3007, 3839, 3021, 3855, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Lava Drags", 3175, 3805, 3221, 3857, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Lava Maze Tele", 3019, 3812, 3044, 3842, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Lever", 3149, 3917, 3162, 3933, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Mage Arena", 3088, 3919, 3123, 3949, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Mage Bank", 3082, 3952, 3103, 3960, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("New Gate", 3345, 3916, 3390, 3957, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("19s", 3220, 3660, 3234, 3672, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Old Gate", 3211, 3882, 3238, 3906, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Pirate Hut", 3037, 3948, 3045, 3959, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Resource Arena", 3174, 3923, 3195, 3946, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Rev Black Drags", 3223, 10190, 3254, 10216, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Rev Cave", 3128, 10059, 3225, 10232, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Rev Dark Beast", 3243, 10136, 3264, 10154, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Rev Entrance", 3118, 3818, 3142, 3837, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Inside Rev Ent.", 3238, 10231, 3243, 10236, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Main Rev Chamber", 3227, 10157, 3261, 10187, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Rogue Castle", 3275, 3920, 3299, 3947, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Rune Rocks", 3055, 3876, 3072, 3890, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Scorpia", 3216, 3935, 3248, 3949, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Single Strip", 3333, 3774, 3348, 3842, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Lvl 18 Rev Ent", 3071, 3645, 3092, 3660, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Spider Hill", 3156, 3871, 3182, 3896, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("13s", 3145, 3609, 3168, 3628, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Venenatis", 3298, 3722, 3353, 3759, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Vet'tion", 3183, 3765, 3227, 3796, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Volcano", 3345, 3916, 3390, 3957, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Web", 3153, 3948, 3163, 3961, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("West Drags", 2960, 3598, 2992, 3627, 0, IMPORTED_PRIORITY));
		locations.add(new WildernessLocation("Wildy Agility Course", 2988, 3906, 3008, 3967, 0, IMPORTED_PRIORITY));
		return locations;
	}
}
