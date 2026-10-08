package interop

import com.jamesward.zioacp.schema.*
import zio.*
import zio.json.ast.Json

/** The fixed values of the cross-SDK step catalogue (java-sdk integration-testing/steps.json "fixtures"). */
object Fixtures:
  val fsReadContent = "line1\nline2\nline3\n"

  val extMethod       = "_interop/ping"
  val extNotification = "_interop/note"
  val extParams: Json = Json.Obj("n" -> Json.Num(1))
  val extResult: Json = Json.Obj("pong" -> Json.Num(1))

  val futureStatus = "interop_future_status"

  val modeA = SessionModeId("interop-mode-a")
  val modeB = SessionModeId("interop-mode-b")

  def modes(current: SessionModeId): SessionModeState =
    SessionModeState(current, List(SessionMode(modeA, "Mode A"), SessionMode(modeB, "Mode B")))

  val modelA = SessionConfigValueId("model-a")
  val modelB = SessionConfigValueId("model-b")

  def modelOption(current: SessionConfigValueId): SessionConfigOption =
    SessionConfigOption.Select(
      SessionConfigId("model"),
      "Model",
      current,
      SessionConfigSelectOptions.Ungrouped(List(SessionConfigSelectOption(modelA, "Model A"), SessionConfigSelectOption(modelB, "Model B"))),
    )

  def verboseOption(current: Boolean): SessionConfigOption =
    SessionConfigOption.Boolean(SessionConfigId("verbose"), "Verbose", current)

  def effortOption(current: SessionConfigValueId): SessionConfigOption.Select =
    SessionConfigOption.Select(
      SessionConfigId("effort"),
      "Effort",
      current,
      SessionConfigSelectOptions.Grouped(
        List(
          SessionConfigSelectGroup(SessionConfigGroupId("fast"), "Fast", List(SessionConfigSelectOption(SessionConfigValueId("effort-low"), "Low"))),
          SessionConfigSelectGroup(
            SessionConfigGroupId("deep"),
            "Deep",
            List(
              SessionConfigSelectOption(SessionConfigValueId("effort-medium"), "Medium"),
              SessionConfigSelectOption(SessionConfigValueId("effort-high"), "High"),
            ),
          ),
        ),
      ),
    )

  val permissionToolCall: ToolCallUpdate =
    ToolCallUpdate(ToolCallId("perm-1"), title = Some("interop permission"), kind = Some(ToolKind.Edit), status = Some(ToolCallStatus.Pending))

  val permissionOptions: List[PermissionOption] = List(
    PermissionOption(PermissionOptionId("allow"), "Allow", PermissionOptionKind.AllowOnce),
    PermissionOption(PermissionOptionId("reject"), "Reject", PermissionOptionKind.RejectOnce),
  )

  val meta: Json.Obj = Json.Obj("interop" -> Json.Str("m1"))

  def hasMeta(m: Option[Json.Obj]): Boolean = m.exists(_.get("interop").contains(Json.Str("m1")))

  val clientInit: InitializeRequest = InitializeRequest(
    ProtocolVersion.V1,
    ClientCapabilities(
      fs = FileSystemCapabilities(readTextFile = true, writeTextFile = true),
      terminal = true,
      session = Some(ClientSessionCapabilities(Some(SessionConfigOptionsCapabilities(boolean = Some(Marker()))))),
      auth = AuthCapabilities(terminal = true),
      elicitation = Some(ElicitationCapabilities(form = Some(Marker()), url = Some(Marker()))),
    ),
    Some(Implementation("interop-scala-client", "1")),
  )

  val agentAuth: AuthMethod = AuthMethod.Agent(AuthMethodId("interop-auth"), "Interop auth", Some("Accepts any authenticate call"))
  val terminalAuth: AuthMethod =
    AuthMethod.Terminal(AuthMethodId("interop-terminal-auth"), "Interop terminal auth", args = List("--login"))

  val agentCapabilities: AgentCapabilities = AgentCapabilities(
    loadSession = true,
    sessionCapabilities = SessionCapabilities(list = Some(Marker()), delete = Some(Marker()), resume = Some(Marker()), close = Some(Marker())),
    auth = AgentAuthCapabilities(logout = Some(Marker())),
  )

  private given CanEqual[Json, Json] = CanEqual.derived

/** `STEP <id> PASS|FAIL (<ms> ms) -> <detail>` lines, the output contract of the cross-SDK runner. */
object Step:
  def line(id: String, pass: Boolean, startNanos: Long, nowNanos: Long, detail: String): String =
    s"STEP $id ${if pass then "PASS" else "FAIL"} (${(nowNanos - startNanos) / 1000000} ms) -> ${detail.replace('\n', ' ').replace('\r', ' ')}"

  /** An agent-side assertion, on stderr: on stdio, stdout is the protocol stream. */
  def agent(id: String, pass: Boolean, startNanos: Long, detail: String): UIO[Unit] =
    Clock.nanoTime.flatMap(now => ZIO.succeed(java.lang.System.err.println(line(s"agent.$id", pass, startNanos, now, detail))))

  def log(message: String): UIO[Unit] = ZIO.succeed(java.lang.System.err.println(message))

  def abbreviate(s: String): String = if s.length > 200 then s"${s.take(200)}... (${s.length} chars)" else s
