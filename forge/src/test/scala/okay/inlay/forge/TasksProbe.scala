package okay.inlay.forge

/** the tasks symdex's index gives, each reference checked against itself: what is usable. Opt-in: PROBE=1 */
class TasksProbe extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(60, "min")

  test("symdex: tasks and their references checked"):
    assume(sys.env.contains("PROBE"), "PROBE=1 to run")
    val home = sys.props("user.home")
    val p = Project(java.nio.file.Path.of(s"$home/work/my/symdex"),
      java.nio.file.Files.readString(java.nio.file.Path.of("/tmp/symdex-cp.txt")).trim,
      s"$home/work/my/symdex/target/symdex/bin/symdex")
    println(s"members of objects: ${p.members.size}")
    val tasks = Tasks.from(p)
    println(s"tasks: ${tasks.size} from ${tasks.map(_.member).distinct.size} members, " +
      s"${tasks.map(_.member.owner).distinct.size} objects; kinds ${tasks.groupBy(_.kind).view.mapValues(_.size).toMap}")
    tasks.take(4).foreach(t => println(s"  ${t.id}: ${t.text}\n    ${t.reference}"))
    val check = java.nio.file.Files.readString(java.nio.file.Path.of(
      s"$home/work/my/symdex/src/main/resources/symdex/Check.scala.txt"))
    val v = Verify(p, check, "3.9.0")
    val t0 = System.nanoTime
    val verdicts = v(tasks.map(t => t -> t.reference))
    println(f"checked in ${(System.nanoTime - t0) / 1e9}%.0f s: ${verdicts.count(_.compiles)} compile, " +
      s"${verdicts.count(_.behaves)} equal to themselves")
    tasks.zip(verdicts).filterNot(_._2.behaves).take(6).foreach((t, d) => println(s"  out: ${t.id} — ${d.detail.take(150)}"))

class GeneralProbe extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(20, "min")
  test("the regression set: every reference compiles and equals itself; a wrong answer is caught"):
    assume(sys.env.contains("PROBE"), "PROBE=1 to run")
    val home = sys.props("user.home")
    val p = Project(java.nio.file.Path.of(s"$home/work/my/symdex"),
      java.nio.file.Files.readString(java.nio.file.Path.of("/tmp/symdex-cp.txt")).trim,
      s"$home/work/my/symdex/target/symdex/bin/symdex")
    val check = java.nio.file.Files.readString(java.nio.file.Path.of(
      s"$home/work/my/symdex/src/main/resources/symdex/Check.scala.txt"))
    val v = Verify(p, check, "3.9.0")
    val verdicts = v(General.tasks.map(t => t -> t.reference))
    General.tasks.zip(verdicts).filterNot(_._2.behaves).foreach((t, d) => println(s"  bad: ${t.id} ${d.detail}"))
    assertEquals(verdicts.count(_.behaves), General.tasks.size)
    val wrong = v(Vector(General.tasks(0) -> """def reverseWords(s: String): String = s.split(" ").reverse.mkString(" ")"""))
    assert(!wrong.head.behaves, "a wrong reverseWords passes")
