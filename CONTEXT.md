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
