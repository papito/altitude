package altitude.core.util

import java.nio.file.Files
import java.nio.file.Path

import scala.jdk.CollectionConverters._
import scala.util.matching.Regex

/**
 * What the dev-only style guide knows about the styles as they are written in the source tree: the design tokens (CSS custom
 * properties) and where they are declared and referenced, the color literals written past them, and the icons in use.
 *
 * A location is `<root directory name>/<path under the root>:<line>`.
 */
object StyleGuideScan:

  /** A token declared in CSS. `note` is the comment directly above the declaration. */
  case class Declaration(name: String, value: String, location: String, note: Option[String])

  /** A token set from JavaScript (`setProperty`) and declared nowhere in CSS */
  case class RuntimeToken(name: String, locations: Seq[String])

  /** A color literal, as spelled, outside any token declaration */
  case class Literal(value: String, locations: Seq[String])

  case class Result(
      rootTokens: Seq[Declaration],
      scopedTokens: Seq[Declaration],
      runtimeTokens: Seq[RuntimeToken],
      references: Map[String, Int],
      literals: Seq[Literal],
      icons: Map[String, Int],
      filesScanned: Int):

    def toJson: ujson.Obj =
      def declarations(tokens: Seq[Declaration]) = tokens.map {
        t =>
          ujson.Obj(
            "name" -> t.name,
            "value" -> t.value,
            "location" -> t.location,
            "note" -> t.note.map(ujson.Str(_)).getOrElse(ujson.Null))
      }

      ujson.Obj(
        "rootTokens" -> declarations(rootTokens),
        "scopedTokens" -> declarations(scopedTokens),
        "runtimeTokens" -> runtimeTokens.map(t => ujson.Obj("name" -> t.name, "locations" -> t.locations)),
        "references" -> ujson.Obj.from(references.view.mapValues(ujson.Num(_))),
        "literals" -> literals.map(l => ujson.Obj("value" -> l.value, "locations" -> l.locations)),
        "icons" -> ujson.Obj.from(icons.view.mapValues(ujson.Num(_)))
      )

  private val EXTENSIONS = Seq(".css", ".js", ".html")
  private val LIB_DIR = "lib"

  private val BLOCK_COMMENTS = Seq("""(?s)/\*.*?\*/""".r, """(?s)<!--.*?-->""".r, """(?s)@\*.*?\*@""".r)
  // Only at the start of a line or after whitespace, which leaves the `//` of a URL alone
  private val LINE_COMMENT = """(?m)(?<=^|\s)//.*$""".r

  private val ROOT_RULE = """:root\s*\{[^}]*\}""".r
  // A declaration starts a line, follows another declaration or opens a rule or an inline style
  private val DECLARATION = """(?<=^|[;{"'\s])(--[a-zA-Z][\w-]*)\s*:\s*([^;}]+)""".r
  private val TOKEN_NAME = """(?<![\w-])--[a-zA-Z][\w-]*""".r
  private val SET_OR_REMOVE = """(?:set|remove)Property\(\s*["'](--[a-zA-Z][\w-]*)["']""".r
  private val SET_PROPERTY = """setProperty\(\s*["'](--[a-zA-Z][\w-]*)["']""".r
  private val COLOR_LITERAL = """#(?:[0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{3,4})(?![\w-])|rgba?\([^)]*\)""".r
  private val ICON = """(?<![\w-])fa-[a-z0-9-]+""".r
  private val ICON_MODIFIER = """fa-(?:spin|pulse|fw|lg|xs|sm|\d+x)""".r

  /** One scanned file: its text with the comments blanked out (offsets and lines kept), and what locates a match in it */
  private case class Source(label: String, original: String, text: String):
    val isStylesheet: Boolean = label.endsWith(".css")
    val isScript: Boolean = label.endsWith(".js")

    def location(offset: Int): String = s"$label:${text.substring(0, offset).count(_ == '\n') + 1}"

  /**
   * Scans the `*.css`, `*.js` and `*.html` files under `roots`, skipping `lib` directories and the files named in
   * `excludedFiles`. A root that does not exist holds no files.
   */
  def scan(roots: Seq[Path], excludedFiles: Set[String] = Set.empty): Result =
    val sources = roots.filter(Files.isDirectory(_)).flatMap(root => readSources(root, excludedFiles))

    // (declaration, is it in a `:root` rule), and the spans the declarations occupy, per source
    val declared = sources.map(source => source -> declarationsOf(source))
    val declarations = declared.flatMap((_, found) => found.map((declaration, isRoot, _) => declaration -> isRoot))
    val declaredNames = declarations.map((declaration, _) => declaration.name).toSet

    val runtimeTokens = sources
      .flatMap(source => SET_PROPERTY.findAllMatchIn(source.text).map(m => m.group(1) -> source.location(m.start)))
      .filterNot((name, _) => declaredNames.contains(name))
      .groupMap(_._1)(_._2)
      .toSeq
      .sortBy(_._1)
      .map(RuntimeToken(_, _))

    val references = declared
      .flatMap((source, found) => referencesIn(source, found.map(_._3)))
      .groupMapReduce(identity)(_ => 1)(_ + _)

    val literals = declared
      .flatMap((source, found) => literalsIn(source, found.map(_._3)))
      .groupMap(_._1)(_._2)
      .toSeq
      .sortBy(_._1)
      .map(Literal(_, _))

    val icons = sources
      .filterNot(_.isStylesheet)
      .flatMap(source => ICON.findAllIn(source.text))
      .filterNot(ICON_MODIFIER.matches)
      .groupMapReduce(identity)(_ => 1)(_ + _)

    Result(
      rootTokens = declarations.collect { case (declaration, true) => declaration },
      scopedTokens = declarations.collect { case (declaration, false) => declaration },
      runtimeTokens = runtimeTokens,
      references = references,
      literals = literals,
      icons = icons,
      filesScanned = sources.length
    )

  private def readSources(root: Path, excludedFiles: Set[String]): Seq[Source] =
    val stream = Files.walk(root)
    try
      stream.iterator.asScala
        .filter(Files.isRegularFile(_))
        .filter {
          file =>
            val name = file.getFileName.toString
            EXTENSIONS.exists(name.endsWith) && !excludedFiles.contains(name) &&
            !root.relativize(file).iterator.asScala.exists(_.toString == LIB_DIR)
        }
        .toSeq
        .sorted
        .map {
          file =>
            val label = s"${root.getFileName}/${root.relativize(file).iterator.asScala.mkString("/")}"
            val original = Files.readString(file)
            // A stylesheet has no line comments, and `//` may start a protocol-relative URL there
            val comments = if label.endsWith(".css") then BLOCK_COMMENTS else BLOCK_COMMENTS :+ LINE_COMMENT
            Source(label, original, comments.foldLeft(original)(blank))
        }
    finally stream.close()

  /** Replaces every match with spaces, keeping line breaks, so offsets and line numbers stay those of the file */
  private def blank(text: String, pattern: Regex): String =
    pattern.replaceAllIn(text, m => Regex.quoteReplacement(m.matched.map(c => if c == '\n' then '\n' else ' ')))

  /** The token declarations of a stylesheet or a template: the declaration, whether a `:root` rule holds it, and its span */
  private def declarationsOf(source: Source): Seq[(Declaration, Boolean, Range)] =
    if source.isScript then Seq.empty
    else
      val rootRules =
        if source.isStylesheet then ROOT_RULE.findAllMatchIn(source.text).map(m => m.start until m.end).toSeq else Seq.empty

      DECLARATION
        .findAllMatchIn(source.text)
        .map {
          m =>
            val declaration = Declaration(
              name = m.group(1),
              value = m.group(2).trim.replaceAll("""\s+""", " "),
              location = source.location(m.start),
              note = noteAbove(source.original, m.start))
            (declaration, rootRules.exists(_.contains(m.start)), m.start until m.end)
        }
        .toSeq

  /** The comment that ends on the line directly above the one holding `offset` and starts a line of its own */
  private def noteAbove(original: String, offset: Int): Option[String] =
    val lineStart = original.lastIndexOf('\n', offset - 1) + 1
    val above = original.substring(0, math.max(lineStart - 1, 0))
    val lastLine = above.substring(above.lastIndexOf('\n') + 1)

    if !lastLine.trim.endsWith("*/") then None
    else
      val commentStart = above.lastIndexOf("/*")
      val beforeComment = above.substring(above.lastIndexOf('\n', commentStart) + 1, commentStart)

      Option.when(commentStart >= 0 && beforeComment.isBlank) {
        above.substring(commentStart + 2, above.lastIndexOf("*/")).trim.replaceAll("""\s+""", " ")
      }

  /** The token names in `source` that neither declare a token (in CSS, or from JavaScript) nor remove one */
  private def referencesIn(source: Source, declarationSpans: Seq[Range]): Seq[String] =
    val notReferences = declarationSpans.map(_.start) ++ SET_OR_REMOVE.findAllMatchIn(source.text).map(_.start(1))

    TOKEN_NAME.findAllMatchIn(source.text).filterNot(m => notReferences.contains(m.start)).map(_.matched).toSeq

  /**
   * The color literals of a stylesheet or a template outside the token declarations, each with its location. A hex literal counts
   * only as a value: after a colon on its line and with no rule opening after it, which leaves out ID selectors and fragment
   * links.
   */
  private def literalsIn(source: Source, declarationSpans: Seq[Range]): Seq[(String, String)] =
    if source.isScript then Seq.empty
    else
      COLOR_LITERAL
        .findAllMatchIn(source.text)
        .filterNot(m => declarationSpans.exists(_.contains(m.start)))
        .filter {
          m =>
            val lineStart = source.text.lastIndexOf('\n', m.start) + 1
            val lineEnd = source.text.indexOf('\n', m.end) match
              case -1 => source.text.length
              case end => end

            !m.matched.startsWith("#") ||
            (source.text.substring(lineStart, m.start).contains(':') && !source.text.substring(m.end, lineEnd).contains('{'))
        }
        .map(m => m.matched -> source.location(m.start))
        .toSeq
