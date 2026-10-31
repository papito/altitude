# Altitude

Altitude organizes and searches a library of media assets.

## Language

**Date Taken**:
The date and time an asset was captured, distinct from when it was added to the
library. Its calendar day follows the camera's recorded date and does not change
with the viewer's timezone.
_Avoid_: Date Added, Date Imported when referring to capture time.

**Date Imported**:
The date and time an asset was added to the library, distinct from when it was
captured. Its calendar day is determined in UTC.
_Avoid_: Date Taken when referring to import time.

**Location**:
A user-defined place: a name and a pin (a WGS84 point placed on a map, never
typed), holding pointers to any number of assets, optionally under a Category.
_Avoid_: Place, Geotag, Pin when referring to the entity rather than its point.

**Category**:
A named container for Locations, one level deep, with no pin and no assets of
its own. Locations and Categories share one name pool per repository.
_Avoid_: Parent, Folder, Group.

**Choice menu**:
A toolbar control that picks one of several values from a popover list, such as
the results grid's Group and Sort; its trigger shows the current choice.
_Avoid_: Dropdown, Select (a select is a form's field).

**Pin**:
The point of a Location, placed by clicking a map or from a place-name search.
_Avoid_: Coordinates, Lat/Long in user-facing text.

**Plotted point**:
Where the map draws an asset of a search: at the asset's own GPS position, or,
when it has none, at the Pin of each Location it is in. Counts on the map count
plotted points, so an asset without a position in two Locations counts twice.
_Avoid_: Marker, Geotag.

**Map area**:
A rectangle of the map that narrows a search to the assets plotted inside it.
A Crowded pin opens one, and "Show only these in the grid" keeps it.
_Avoid_: Bounding box, bbox in user-facing text.

**Crowded pin**:
A map pin standing for several Plotted points, shown as a representative
thumbnail with a count. It zooms in until its points separate, and opens their
Map area when they cannot.
_Avoid_: Cluster in user-facing text.

**Date Group**:
The assets in a search result whose selected date, either Date Taken or Date
Imported, falls on the same calendar day.

**Video**:
An asset whose media type is video: a stored original with a duration, one
Preview, and Faces each pinned to a Frame time.
_Avoid_: Movie, Clip, Film.

**Preview**:
The small still image standing for an asset in the grid and on the map. For a
Video it is one of its Sampled frames.
_Avoid_: Thumbnail, Poster.

**Sampled frame**:
One of the evenly spaced frames of a Video from which its Faces and its Preview
are chosen.
_Avoid_: Keyframe.

**Frame time**:
The moment within a Video, measured from its start, that a Face or the Preview
was taken from.
_Avoid_: Timestamp, Offset, Position.

**Face quality**:
How recognizable a detected face is: the norm of its raw embedding, higher is
better. It drops with blur and occlusion and decides the face's tier.
_Avoid_: Confidence, Score (the detector's), Sharpness.

**Enrolled face**:
A Face whose quality clears the enroll threshold: it may start a new Person and
is a candidate when other faces are matched.
_Avoid_: Good face, Reference face.

**Match-only face**:
A Face of lesser quality, or seen in too few Sampled frames of a Video: it may
join a known Person but never starts one, is never a match candidate, and is
dropped when it matches nobody.
_Avoid_: Weak face, Low-quality face in user-facing text.

**Matched person**:
A Person the library treats as established: at least three Faces, or named.
Not about face matching: a Matched person is one whose faces have accumulated.
_Avoid_: Verified, Complete, Above threshold.

**Unmatched person**:
A Person with fewer than three Faces and no name: a merge candidate or noise.
_Avoid_: Unverified, Incomplete, Below threshold.

**Hidden person**:
A Person taken off the People list by the user; its Faces still match. Being
hidden overrides the tier: a hidden person is neither Matched nor Unmatched in
the list.
_Avoid_: Suppressed, Invisible, Archived.

**People bucket**:
One of Matched, Unmatched or Hidden; every listed Person is in exactly one. The
People tab shows the union of the buckets switched on, Matched alone by default.
_Avoid_: Type filter, Quality category, Tier (a Face has a tier, a Person a
bucket).

**Split view**:
The People tab divided into two panes, each with its own People buckets, so a
person in one pane can be dragged onto a person in the other. The panes stack
when the Dock side is left or right and sit side by side when it is top or
bottom.
_Avoid_: Dual pane, Clone, Mirror.

**Main pane**:
The People tab's first pane, always shown, which carries the Split view switch.
_Avoid_: Top pane, Left pane, Primary pane.

**Duplicate pane**:
The People tab's second pane, shown only in Split view, with People buckets of
its own.
_Avoid_: Bottom pane, Right pane, Secondary pane, Clone.

**Results title**:
The block above the results toolbar naming what the results are scoped to: a
Person, a folder's path, an album, a Location or the Search text, with the
way back to the whole result set.
_Avoid_: Header, Banner, Scope bar.

**Search text**:
What the user types into the nav's quick search input: Search terms, optionally joined by
`OR`, excluded with a leading `-`, or quoted into a phrase. It always searches
the whole repository outside the trash.
_Avoid_: Query, Keywords, Search string in user-facing text.

**Search term**:
One word or one quoted phrase of the Search text. A word matches the start of a
word; a phrase matches whole consecutive words within a single name.
_Avoid_: Token, Keyword.

**Search source**:
A kind of name a Search term can match: a Person, a Location, a Category, a
folder on the asset's folder path, an album, or the asset's Search document.
_Avoid_: Field, Facet.

**Search document**:
The searchable words kept for one asset itself: those of its file name and of
its user metadata values. Names of people, Locations, Categories, folders and
albums are not part of it.
_Avoid_: Index entry, Search index when referring to one asset's words.

**Relevance**:
How strongly an asset matches the Search text: each Search term counts for the
most important Search source it matched (Person, then Location, Category, folder
or album, Search document), and the terms add up.
_Avoid_: Rank, Score, Weight in user-facing text.

**Admin**:
The account type that set the system up. It owns the repositories and alone
may change Settings.
_Avoid_: Owner, Superuser, Root.

**Setting**:
A system-wide tunable value with a default, changed by an Admin on the Settings
screen. It is either a Live setting or a Restart setting.
_Avoid_: Config, Option, Preference, Parameter, Tunable.

**Live setting**:
A Setting whose new value is used from the next operation that reads it, with
no restart.
_Avoid_: Hot setting, Dynamic setting.

**Restart setting**:
A Setting whose new value is used only after the next server start. Until then
it has both a saved value and a running value.
_Avoid_: Static setting, Boot setting.

**Dock side**:
Where the explorer sits against the results: left, right, top or bottom. The
explorer is docked to a side; the results take the rest.
_Avoid_: Layout (the results' Grid or Map), Position, Arrangement, Orientation.

**View settings**:
Which details the grid shows under each asset, chosen per browser tab in the
⚙ View popover. They are not Settings.
_Avoid_: Display options, Grid options.
