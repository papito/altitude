package altitude.core.unit

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers.*

import scala.jdk.CollectionConverters.*
import scala.meta.*

/**
 * Where transactions live, read from the application's sources: a service owns them, and nothing else opens one. The check is
 * syntactic, so it cannot see an operation that loops over several wrapped calls with no transaction around them; that rule is in
 * `altitude/AGENTS.md`.
 */
@DoNotDiscover class TransactionBoundaryTests extends AnyFunSuite {
  import TransactionBoundaryTests.*

  test("A service method that reaches a DAO opens or joins a transaction itself") {

    /**
     * Setup:
     *
     * Every Scala source under altitude/core/service, parsed with Scalameta.
     *
     * Assertions:
     *
     * Every DAO reference in a non-private service method sits inside a transaction wrapper (withTransaction, asReadOnly or
     * withFaceVector) within that method; any that does not is listed by file, line and method.
     *
     * Edge cases:
     *
     * A private helper is exempt, as its caller's wrapper covers it, and a DAO's companion object does not count as a DAO.
     */
    val violations = for {
      file <- sources("service")
      ref <- file.tree.collect { case select: Term.Select if isDaoReference(select) => select }
      method <- memberDef(ref)
      if !method.mods.exists(_.is[Mod.Private]) && !isInWrapper(ref, method)
    } yield s"${file.path}:${ref.pos.startLine + 1} ${method.name.value}: $ref"

    violations shouldBe empty
  }

  test("Only services open transactions") {

    /**
     * Setup:
     *
     * Every Scala source under altitude/core/dao, pipeline and routes, parsed with Scalameta.
     *
     * Assertions:
     *
     * None of them names the transaction manager or any of its wrappers.
     */
    val violations = for {
      file <- sources("dao") ++ sources("pipeline") ++ sources("routes")
      name <- file.tree.collect { case name: Term.Name if TransactionNames.contains(name.value) => name }
    } yield s"${file.path}:${name.pos.startLine + 1}: ${name.value}"

    violations shouldBe empty
  }

  test("Pipeline flows and controllers reach DAOs only through services") {

    /**
     * Setup:
     *
     * Every Scala source under altitude/core/pipeline and routes, parsed with Scalameta.
     *
     * Assertions:
     *
     * None of them selects the app's DAO registry, so pipeline flows and controllers reach data only through services.
     */
    val violations = for {
      file <- sources("pipeline") ++ sources("routes")
      select <- file.tree.collect { case select @ Term.Select(_, Term.Name("DAO")) => select }
    } yield s"${file.path}:${select.pos.startLine + 1}: $select"

    violations shouldBe empty
  }
}

object TransactionBoundaryTests {
  private val Wrappers = Set("withTransaction", "asReadOnly", "withFaceVector")
  private val TransactionNames = Wrappers + "txManager"

  private case class SourceFile(path: String, tree: Tree)

  /** Every source file under `altitude/core/<dir>`, parsed */
  private def sources(dir: String): List[SourceFile] = {
    val root = Paths.get(sys.env("ALTITUDE_SOURCE_DIR"))
    val walk = Files.walk(root.resolve(s"altitude/core/$dir"))
    try
      walk.iterator.asScala
        .filter(path => Files.isRegularFile(path) && path.toString.endsWith(".scala"))
        .map(path => SourceFile(root.relativize(path).toString, parse(path)))
        .toList
    finally walk.close()
  }

  private def parse(path: Path): Tree =
    dialects.Scala3(Input.VirtualFile(path.toString, Files.readString(path))).parse[Source].get

  /**
   * A member selected on a DAO: on `dao`, on a field named like `searchDao`, or on `app.DAO.<name>`; a DAO's companion object
   * (`SearchDao.FACETED_FIELD_TYPES`) is not a DAO
   */
  private def isDaoReference(select: Term.Select): Boolean = select.qual match {
    case Term.Name(name) => name == "dao" || (name.head.isLower && name.endsWith("Dao"))
    case Term.Select(_, Term.Name("DAO")) => true
    case _ => false
  }

  /** The class member a reference is in: its outermost enclosing `def`, none for a field */
  private def memberDef(tree: Tree): Option[Defn.Def] =
    ancestors(tree).takeWhile(!_.is[Template]).collect { case method: Defn.Def => method }.lastOption

  /** Whether a reference is inside a transaction wrapper within the method, such as `txManager.asReadOnly[Stats] { ... }` */
  private def isInWrapper(ref: Tree, method: Defn.Def): Boolean =
    ancestors(ref).takeWhile(_ ne method).exists {
      case apply: Term.Apply => isWrapper(apply.fun)
      case _ => false
    }

  private def isWrapper(fun: Term): Boolean = fun match {
    case applyType: Term.ApplyType => isWrapper(applyType.fun)
    case Term.Select(Term.Name("txManager") | Term.Select(_, Term.Name("txManager")), Term.Name(name)) =>
      Wrappers.contains(name)
    case _ => false
  }

  private def ancestors(tree: Tree): List[Tree] =
    Iterator.iterate(tree.parent)(_.flatMap(_.parent)).takeWhile(_.isDefined).flatten.toList
}
