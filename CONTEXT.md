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

**Search text**:
What the user types into the search input: Search terms, optionally joined by
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
