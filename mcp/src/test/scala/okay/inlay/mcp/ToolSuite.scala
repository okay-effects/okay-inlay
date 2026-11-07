package okay.inlay.mcp

import okay.effects.json.Json
import okay.effects.json.Json.{Str, num, obj}
import okay.effects.machine.Machine
import okay.inlay.bridge.Bridge
import okay.mcp.Rpc

/** THE TOOL OVER THE PROTOCOL: a scripted client — initialize, list, call — and the tool's answer the bridge's answer,
 * the same program the bridge's law holds for */
class ToolSuite extends munit.FunSuite:
  import Machine.*
  import Main.servers.{links, serve}

  test("initialize, list, call: the answer is the bridge's"):
    val text = "Ann manages Bob.\nBob oversees Cid.\nDee reports to Cid.\nThe weather was fine.\nDee leads Bob."
    val script = List(
      Rpc.Request(num(1), "initialize", obj("protocolVersion" -> Str("2025-06-18"), "capabilities" -> Json.Obj.empty,
        "clientInfo" -> obj("name" -> Str("test")))),
      Rpc.Notify("notifications/initialized", Json.Obj.empty),
      Rpc.Request(num(2), "tools/list", Json.Obj.empty),
      Rpc.Request(num(3), "tools/call", obj("name" -> Str("above"),
        "arguments" -> obj("text" -> Str(text), "a" -> Str("Ann"), "b" -> Str("Dee")))),
      Rpc.Request(num(4), "tools/call", obj("name" -> Str("above"), "arguments" -> obj("text" -> Str(text)))))
      .map(Rpc.encode)
    val (sent, _) = run(links.scripted(script)(serve[Main.L](Main.server[Main.L])))
    val replies = sent.map(Rpc.decode)
    assert(replies.exists(_.exists { case Rpc.Answer(_, r) => Json.print(r).contains("\"above\""); case _ => false }))
    val call = replies.collectFirst { case Right(Rpc.Answer(id, r)) if id == num(3) => Json.print(r) }.get
    println(call)
    val said = Tool.above(text, "Ann", "Dee")
    assert(call.contains("Ann is above Dee"), call)
    assert(call.contains("lines 2, 3, 5 form a cycle"), call)
    assert(call.contains("not understood: lines 4"), call)
    assertEquals(said.isError, false)
    val bad = replies.collectFirst { case Right(Rpc.Answer(id, r)) if id == num(4) => Json.print(r) }.get
    assert(bad.contains("isError"), bad)
    assert(Bridge.names.contains("Ann"))
