package altitude.core.models

/**
 * What the scope dialog of a click in the Triage explorer describes (htmx/triage_scope_dialog), with the Browse count it states:
 * the assets Browse shows by default for it, none triaged or recycled
 */
enum TriageScope:
  // Any folder but the root, with the assets of its subfolders in its count
  case InFolder(folder: Folder, browseCount: Int)
  // A Person with no triaged assets
  case OfPerson(person: Person)
  // A Location with no triaged assets; a count of none means it has no assets at all
  case InLocation(location: Location, browseCount: Int)
