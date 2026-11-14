# Altitude

Altitude organizes and searches a library of media assets.

## Language

**Library**:
A collection of assets kept apart from every other: its own folders, albums,
People, Locations, Triage and Trash. Nothing crosses between Libraries, and the
user works in one at a time. It belongs to the server, not to an account: an
Admin, or an account allowed to create Libraries, creates it, and its name is
unique on the server, whatever its case or spacing.
_Avoid_: Repository, Repo, Catalog, Collection in user-facing text.

**Storage**:
Where a Library keeps its originals, Previews and face images: This server, or
an Object store. It is chosen when the Library is created and does not change.
_Avoid_: Backend, Store, File store, Storage location in user-facing text.

**Object store**:
An S3-compatible service that the server's operator has set up and named, on
which a Library can keep its files instead of on the server's own disk.
_Avoid_: Bucket (a People bucket is something else), S3, Cloud storage, Remote
storage.

**Date Taken**:
The date and time an asset was captured, distinct from when it was added to the
library. Its calendar day follows the camera's recorded date and does not change
with the viewer's timezone.
_Avoid_: Date Added, Date Imported when referring to capture time.

**Date Imported**:
The date and time an asset was added to the library, distinct from when it was
captured. Its calendar day is a day in the server's time zone.
_Avoid_: Date Taken when referring to import time.

**Import**:
One delivery of files to a Library, kept as a record: one upload from a browser,
of chosen files or of a whole folder tree, or one scan of a Watch folder that
found files. Its files land in Triage, or skip it: sorted into one chosen
folder, or, when the Import keeps their folder structure, into folders that
mirror it.
_Avoid_: Batch (a user's selection), Job, Run, Upload when referring to the
record.

**Import file**:
One file of an Import with its outcome: imported, already in the Library,
unsupported, or failed. Until it has one it is pending.
_Avoid_: Entry, Item, Line.

**Watch folder**:
A directory on the server that the app scans for new files and imports into one
Library, including files that arrived while the app was not running. It is not
a folder of the Library, and it may delete the files it has imported and those
the Library already has. An Admin sets it up, and owns the assets it imports.
_Avoid_: Hot folder, Inbox, Drop folder, Monitored folder.

**Location**:
A user-defined place: a name and a pin (a WGS84 point placed on a map, never
typed), holding pointers to any number of assets, optionally under a Category.
_Avoid_: Place, Geotag, Pin when referring to the entity rather than its point.

**Category**:
A named container for Locations, one level deep, with no pin and no assets of
its own. Locations and Categories share one name pool per Library.
_Avoid_: Parent, Folder, Group.

**Name key**:
The folded form of a name under which two names are the same name: names that
differ only in letter case, in any script ("Weiß" and "WEISS"), or in how their
characters are encoded (a combining accent, a full-width letter, a ligature)
share one, while names that differ in an accent ("Émile" and "Emile") do not.
Libraries, folders among their siblings, albums, Locations and Categories,
metadata fields and People are unique under their names' keys, an asset's
metadata values are one value under one key, and a keyword filter matches under
it. The key is never shown: a name is shown with its own case and letters.
_Avoid_: Lowercase name, Case-insensitive name, Normalized name (the name as
stored).

**Choice menu**:
A control that picks one of several values from a popover list, such as the
results grid's Group and Sort, or the nav's Library menu, opened by the current
Library's name beside Browse; its trigger shows the current choice.
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
Video it is one of its Sampled frames. An edited asset's Preview shows its Edit,
except in Curation, which shows the original's.
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
A Person the library treats as established: a Face on at least three assets
outside the trash, or named. Not about face matching: a Matched person is one
whose faces have accumulated.
_Avoid_: Verified, Complete, Above threshold.

**Unmatched person**:
A Person with a Face on fewer than three assets outside the trash and no name:
a merge candidate or noise.
_Avoid_: Unverified, Incomplete, Below threshold.

**Unknown person**:
A Person with no name that is neither hidden nor discarded, whatever its
bucket: an Unmatched person, or a Matched one whose faces made it established.
_Avoid_: Unnamed person, Stranger, Unidentified.

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
Person, a folder's path, an album, a Location, a Triage subset (the assets one
of the Triage summary's counts shows) or the Search text, with the way back to
the whole result set. A Filtered search's is "Search results" over its Search
filters.
_Avoid_: Header, Banner, Scope bar.

**Search text**:
What the user types into the nav's quick search input, or the Advanced search's
text field, which holds the same text: Search terms, optionally joined by `OR`,
excluded with a leading `-`, or quoted into a phrase. It searches the whole
Library outside the trash, or as much of it as the other Search filters of an
Advanced search leave.
_Avoid_: Query, Keywords, Search string in user-facing text.

**Advanced search**:
The panel under the nav's quick search that combines Search filters into one
search, and the search it runs. It searches the whole Library outside the trash,
narrowed by every filter at once. A folder's Search here opens it with that
folder chosen.
_Avoid_: Filter panel, Search builder, Query builder in user-facing text.

**Search filter**:
One thing an Advanced search narrows by: the Search text, one folder (with the
folders below it), people (all of them, or any of them), Locations (any of
them), a Date Taken range or a Date Imported range of whole days. Every filter
must hold for an asset to match.
_Avoid_: Facet, Criterion, Condition.

**Filtered search**:
A search with a date range, two people or more, two Locations or more, or more
than one of the Search text, a folder, a person and a Location: one that no
single scope names. Its Results title shows each Search filter as a chip that
removes it, and Edit, which opens the Advanced search on it.
_Avoid_: Advanced search when referring to the results, Combined search, Multi-search.

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
The account type that may do everything in every Library. It alone changes
Settings, sets up Watch folders, creates another Admin, and edits, deactivates
or deletes an account.
There can be several; the last active one cannot be removed.
_Avoid_: Owner, Superuser, Root.

**Guest**:
The account type that can only look at the Libraries shared with it. It never
holds a Permission.
_Avoid_: Client, Viewer, Read-only user.

**Permission**:
One thing a User may do in one Library, granted per account and per Library:
Import, Curate own, Curate all, Organize, Edit metadata, Create Users, Create
Guests, Recycle, Purge, Edit images. Looking needs none. Creating Libraries is
allowed per account, not per Library.
_Avoid_: Role, Right, Privilege, Policy.

**Organize**:
The Permission to arrange a Library: sort assets out of Triage into folders,
move sorted assets, manage folders, albums, Locations and Categories, and merge
or hide People. An upload that skips Triage needs it as well as Import.
_Avoid_: Add to Catalog, Add to Library, Manage.

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
Where a docked panel sits: left, right, top or bottom. The explorer is docked
to a side of the results, and the Detail panel to a side of Asset detail's
media; each has its own Dock side. The Edit panel has none of its own: it
takes the side opposite the Detail panel's.
_Avoid_: Layout (the results' Grid or Map), Position, Arrangement, Orientation.

**Asset detail**:
The dialog that shows one asset at full size, stepping to the previous or next
asset of the results. While it is open the URL names the asset and whether its
Detail panel is open; opened from that URL it shows over the default results,
with no previous or next.
_Avoid_: Lightbox, Image detail, Viewer.

**Detail panel**:
One of Asset detail's two docked panels, with the Edit panel, holding the
asset's People and its Details.
_Avoid_: Metadata panel, Info panel, Sidebar.

**Face highlight**:
A Face's box drawn on the media, with its Person's name in a badge under it.
_Avoid_: Bounding box, Face box, Tag in user-facing text.

**Edit panel**:
One of Asset detail's two docked panels, in which a user with Edit images
changes a still image's Edit: its sections Crop & rotate, Light, Color and
Effects, with Undo and Redo. It sits on the side opposite the Detail panel.
Closing it, closing Asset detail or stepping to another asset while a change
is not saved yet asks whether to save it, discard it or keep editing.

**Edit**:
A still image's adjustments, made in the Edit panel. The server keeps one Edit
per asset, the one last saved; each change saves it. Undo and Redo go through
the Edits made while the Edit panel is open on the asset, and are forgotten
when the panel closes, Asset detail closes or steps to another asset, or a save
finds the Edit changed elsewhere or fails. Revert to original
saves an Edit that changes nothing, which Undo takes back while the panel
stays open. The original file is never written. An asset is edited when its
Edit changes a pixel. It is not the Edit button beside a Filtered search's
chips, which opens the Advanced search.
_Avoid_: Recipe, Develop settings, Adjustment layer in user-facing text.

**Effects**:
The Edit panel's section of Sharpen, Clarity, Vignette and Grain.

**Crop tool**:
The Edit panel's tool that shows the whole image on Asset detail's stage under
a crop rectangle to move and resize; a drag outside the rectangle rotates the
image. Done saves all it changed as one change of the Edit, and Cancel drops
it.

**Level tool**:
A tool of the Crop tool: a line drawn along something level or plumb sets
Straighten so that the line becomes horizontal or vertical, whichever is
nearer.

**Display image**:
The image Asset detail shows of an edited asset in place of its original: its
Edit rendered by the server, at most 3840 px on the long edge. Anyone who may
see the asset sees it, and Show original shows the original instead.
_Avoid_: Rendition, Proxy, Thumbnail.

**Show original**:
How Asset detail shows an edited asset's untouched original, to every viewer,
Guests included: while `\` is held, or from a press of the Show original toggle
until it is pressed again, Asset detail steps to another asset, or the Edit
panel opens or makes a change. Face highlights and the size shown are then the
original's.

**View settings**:
Which details the grid shows under each asset, chosen per browser tab in the
⚙ View popover. They are not Settings.
_Avoid_: Display options, Grid options.

**Browse**:
The view of the sorted library, where the explorer finds assets by folder,
album, Person or Location. It can also show triaged assets on request.
_Avoid_: Browse mode, Repository view in user-facing text.

**Triage**:
The view of imported assets not yet sorted into a folder. The explorer there
manages folders, Locations and People, and searches People and Locations
among the triaged assets alone; a folder holds no triaged asset, so it is
only described.
_Avoid_: Triage mode, Inbox, Unsorted.

**Triage summary**:
The sentence above the Triage explorer's tabs stating how many assets are in
triage, how many of them have an Unknown person, and how many are in no
Location; each of the last two counts above zero shows those assets.
_Avoid_: Banner, Callout, Tally, Info message.

**Quick view**:
A wide dialog showing a folder's first 100 assets from Triage, to look at
only, under the folder's path.
_Avoid_: Preview (a single asset's still), Peek, Folder preview.

**Sequence**:
Still images in triage that the import found shot as one scene: within a
minute of each other by the camera's clock or, failing that, by the time in
the file name, on the same camera or both naming none, looking alike by
their Scene embeddings, or by their perceptual hashes where either has none;
or, for images with no capture time, looking alike among the recently
imported images of their size.
An image is in at most one Sequence, and a Sequence ends when its last image
leaves triage or is purged. A kept frame leaves its Sequence when the
Curation set resolved holds all of that Sequence's frames still out of the
trash, and a Sequence also ends when its last frame leaves that way.
_Avoid_: Batch (a user's selection), Burst, Stack, Group, Series.

**Scene embedding**:
A vector a vision model computes over a whole still image, describing its
content and layout. Two Scene embeddings of one model compare by cosine
similarity, 1 for the same picture; embeddings of different models never
compare. A frame has one per Scene model.
_Avoid_: Vector, Feature vector, Image embedding.

**Scene model**:
One of the vision models the import embeds every still image with, DINOv2 or
CLIP. The Sequence rule reads both: two frames alike by either are one
Sequence.
_Avoid_: Encoder, Backbone, Vision model.

**Image quality**:
How technically good a still image is, from its sharpness and exposure, on a
scale of 0 to 100. It is about the whole image, where Face quality is about one
face.
_Avoid_: Score, Rating, Quality alone where it could mean Face quality.

**Face sharpness**:
How much fine detail a Face's box holds at a fixed scale. It falls as a face fills
more of the frame, so it compares only between Faces of one Person at a similar
size.
_Avoid_: Face quality (how recognizable a face is).

**Curation**:
The modal where the user keeps the best frames of a Curation set and recycles
the rest. The button that opens it on a selection reads "Curate Selected", and
the one that opens it on the queue reads "Curate all 3 sequences", with the count.
_Avoid_: Culling, Review.

**Curation set**:
The frames being compared: the members of one Sequence still out of the trash,
the first 100 when it has more, or a selection.
_Avoid_: Batch (a user's selection before it is curated), Group.

**Sequence rail**:
The column of Curation listing the Sequences it opened to walk, the one on show
marked and those already dealt with done. A selection or a single Sequence has
none.
_Avoid_: Sequence panel, Queue panel, Sidebar.

**Pick**:
A frame the user keeps. A set can have several. Its button and its mark read
"Keep".
_Avoid_: Winner, Best.

**Pinned frame**:
The frame held in the stage's left pane to compare the focused frame against.
Pinning keeps nothing: Resolve keeps the Picks, or with none the one frame on
the stage. It has nothing to do with a Location's **Pin**.
_Avoid_: Compared frame, Reference.

**Bundle**:
The frames of a Curation set whose main problem is the same.
_Avoid_: Stack, Group, Batch.

**Contender**:
A frame of a Curation set in no Bundle.
_Avoid_: Candidate (a Sequence's and a face match's word).

**Too close to call**:
A Curation set with no Bundle: the measurements cannot separate its frames.

**Loupe**:
The lens that rides with the pointer over a frame on Curation's stage and shows
the spot under it at the original's pixels, or a multiple of them. Its level is
one value for every frame, stepped by the wheel over a pane, and kept
for the next open. It changes nothing about the frame.
_Avoid_: Magnifier, Magnifying glass, Zoom (the stage's zoom to a Person's
face).

**Histogram**:
The red, green and blue shapes and the luminance line of an image, with the
fractions of its pixels crushed and blown in its corners. Under a frame on
Curation's stage it is measured at import from the pixels the exposure
measurements read, and shown or hidden by one switch for the stage, kept for
the next open. In the Edit panel it reads the edited image as it is drawn.
_Avoid_: Levels, Tone curve.

**Clipping blink**:
The flashing marks over an image on a stage of its blown highlights and crushed
shadows, at the levels the measurement counts them. The Histogram is its
switch, one for the stage; on Curation's stage it is off when Curation opens
and while the Histogram is hidden. In the Edit panel it reads the edited image
as it is drawn.
_Avoid_: Blinkies, Zebra, Clipping warning, Overexposure mask.
