# Altitude

Altitude organizes and searches a library of media assets.

## Language

**Date Taken**:
The date and time an image was captured, distinct from when it was added to the
library. Its calendar day follows the camera's recorded date and does not change
with the viewer's timezone.
_Avoid_: Date Added, Date Imported when referring to capture time.

**Date Imported**:
The date and time an image was added to the library, distinct from when it was
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

**Date Group**:
The images in a search result whose selected date, either Date Taken or Date
Imported, falls on the same calendar day.
