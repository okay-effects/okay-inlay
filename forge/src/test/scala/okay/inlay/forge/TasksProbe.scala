package okay.inlay.forge

/** the tasks symdex's index gives, each reference checked against itself: what is usable. Opt-in: PROBE=1 */
class TasksProbe extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(60, "min")

  test("symdex: tasks and their references checked"):
    assume(sys.env.contains("PROBE"), "PROBE=1 to run")
    val home = sys.props("user.home")
    val p = Project(java.nio.file.Path.of(s"$home/work/my/symdex"),
      java.nio.file.Files.readString(Prepare.home.resolve("symdex.cp")).trim,
      s"$home/work/my/symdex/target/symdex/bin/symdex")
    println(s"members of objects: ${p.members.size}")
    val tasks = Tasks.from(p)
    println(s"tasks: ${tasks.size} from ${tasks.map(_.member).distinct.size} members, " +
      s"${tasks.map(_.member.owner).distinct.size} objects; " +
      s"kinds ${tasks.groupBy(_.kind).view.mapValues(_.size).toMap}")
    tasks.take(4).foreach(t => println(s"  ${t.id}: ${t.text}\n    ${t.reference}"))
    val check = java.nio.file.Files.readString(java.nio.file.Path.of(
      s"$home/work/my/symdex/src/main/resources/symdex/Check.scala.txt"))
    val v = Verify(p, check, "3.9.0")
    val t0 = System.nanoTime
    val verdicts = v(tasks.map(t => t -> t.reference))
    println(f"checked in ${(System.nanoTime - t0) / 1e9}%.0f s: ${verdicts.count(_.compiles)} compile, " +
      s"${verdicts.count(_.behaves)} equal to themselves")
    tasks.zip(verdicts).filterNot(_._2.behaves).take(6)
      .foreach((t, d) => println(s"  out: ${t.id} — ${d.detail.take(150)}"))

class GeneralProbe extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(20, "min")
  test("the regression set: every reference compiles and equals itself; a wrong answer is caught"):
    assume(sys.env.contains("PROBE"), "PROBE=1 to run")
    val home = sys.props("user.home")
    val p = Project(java.nio.file.Path.of(s"$home/work/my/symdex"),
      java.nio.file.Files.readString(Prepare.home.resolve("symdex.cp")).trim,
      s"$home/work/my/symdex/target/symdex/bin/symdex")
    val check = java.nio.file.Files.readString(java.nio.file.Path.of(
      s"$home/work/my/symdex/src/main/resources/symdex/Check.scala.txt"))
    val v = Verify(p, check, "3.9.0")
    val verdicts = v(General.tasks.map(t => t -> t.reference))
    General.tasks.zip(verdicts).filterNot(_._2.behaves).foreach((t, d) => println(s"  bad: ${t.id} ${d.detail}"))
    assertEquals(verdicts.count(_.behaves), General.tasks.size)
    val wrong = v(Vector(General.tasks(0) ->
      """def reverseWords(s: String): String = s.split(" ").reverse.mkString(" ")"""))
    assert(!wrong.head.behaves, "a wrong reverseWords passes")

/** per prepared project: members, tasks generated, usable, and why the rest fall. Opt-in: PROBE=1 */
class ProjectsProbe extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(120, "min")
  test("the prepared projects: what the generator makes of each"):
    assume(sys.env.contains("PROBE"), "PROBE=1 to run")
    val home = sys.props("user.home")
    val check = java.nio.file.Files.readString(java.nio.file.Path.of(
      s"$home/work/my/symdex/src/main/resources/symdex/Check.scala.txt"))
    val names = sys.env.getOrElse("PROBE_PROJECTS", "okay-simple,okay-store").split(",").toVector
    for name <- names do
      val root = Prepare.home.resolve("projects").resolve(name)
      val cp = java.nio.file.Files.readString(Prepare.home.resolve(s"$name.cp")).trim
      val scala = """scala3-library_3/([0-9.]+)/""".r.findFirstMatchIn(cp).map(_.group(1)).getOrElse("3.3.8")
      val p = Project(root, cp, s"$home/work/my/symdex/target/symdex/bin/symdex")
      val ts = Tasks.from(p)
      val withDecl = p.members.count(m => p.declaration(m).isDefined)
      println(s"$name: ${p.symbols.size} symbols, ${p.members.size} object members, $withDecl declared, " +
        s"${ts.size} tasks")
      p.members.take(3).foreach(m => println(s"  member ${m.owner}.${m.name}: ${p.declaration(m)}"))
      if ts.nonEmpty then
        val v = Verify(p, check, scala)(ts.take(12).map(t => t -> t.reference))
        println(s"  first ${v.size}: ${v.count(_.behaves)} usable")
        ts.take(12).zip(v).filterNot(_._2.behaves).take(3)
          .foreach((t, d) => println(s"    out ${t.id}: ${d.detail.take(600)}"))
