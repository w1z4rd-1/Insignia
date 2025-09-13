# Insignia
# WARNING, THIS CURRENTLY GETS YOU KICKED FOR INVALID PACKETS, MAYBE ILL FIX IT IN THE FUTURE MAYBE I WONT!
A Minecraft Fabric mod that enhances your Crystal PvP experience by categorizing players with custom tags and tracking their stats. Insignia adds colored tags to players in the tab menu and provides easy access to Crystal 1v1 statistics.

## Features

- Add colored tags to players in the tab list
- Automatic stats display when players join the queue

## Commands

### Tag Management

- `/tag <playername> <tag> [color]` - Assign a tag with optional color
  - Suggested tags: Runner, Competent, Skilled, Creature (tab completion available)
  - Available colors: black, dark_blue, dark_green, dark_aqua, dark_red, dark_purple, gold, gray, dark_gray, blue, green, aqua, red, light_purple, yellow, white (tab completion available)
  - Default colors are automatically applied based on tag if no color is specified:
    - Runner: Dark Red
    - Competent: Blue (dark_purple)
    - Skilled: Cyan (aqua)
    - Creature: Red
    - T: Gold (hidden tag for tracked players)
    - Private: Blue (hidden tag for players with private stats)
    - Others: White

- `/tag remove <playername>` - Remove a player's tag
- `/tag help` - Display help information about the tagging system

### Stats Checking

- `/sc <playername>` - Check a player's Crystal 1v1 stats
  - Displays wins, win percentage, current win streak, and best win streak
  - Color-coded values indicate skill level
  - Automatically stores the stats for future reference
  - Tags players with private stats as "Private" (blue color in tab list)

## Automatic Queue Stats Display

When a player joins the queue, their stats are immediately displayed in chat:
- Shows their tag (if any) and Crystal 1v1 stats
- Shows how many days ago the stats were checked if older than 2 weeks
- Includes any custom tags and colors you've assigned

## Ghost Totem Detection

Automatically detects and announces when a "ghost totem" event occurs.

- Detects ghost totems in both main hand and off-hand
- Works when switching to a totem slot via hotbar keys or replenishing mainhand or offhand totem
- Announces to chat: `[INSIGNIA] <ghost totem detected> totem held for X ms (Y ticks)`
- Supports detection in both vanilla death and servers that put players in spectator mode on death

## Installation

1. Install Fabric loader for Minecraft
2. Place the Insignia mod jar file in your `.minecraft/mods` folder
3. Launch Minecraft with the Fabric profile

## Compatibility

- Compatible with other Fabric mods
- Does not modify player nametags (compatible with totem pop counters and similar mods)
- Only affects the tab list and chat messages

## Notes
- USE AT YOUR OWN RISK, legacy does not have a stats api, and im unsure how they feel about data scraping like this
- Stats are only shown for Crystal 1v1, other stats are not tracked
- more features to come
