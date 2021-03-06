package scala.scalanative
package testinterface

import java.io.{
  ByteArrayInputStream,
  ByteArrayOutputStream,
  DataInputStream,
  DataOutputStream
}

import scala.scalanative.posix._
import scala.scalanative.native._
import scala.scalanative.runtime.ByteArray
import arpa.inet._
import sys.socket
import netinet.{in, inOps}, in._, inOps._
import sbt.testing.{Event => SbtEvent, _}

import scala.scalanative.testinterface.serialization._
import scala.annotation.tailrec
import scala.scalanative.posix.inttypes.uint16_t
import scala.scalanative.posix.sys.socket.{accept, sockaddr, socklen_t}

abstract class TestMainBase {

  /** All the frameworks reported in `loadedTestFrameworks` in sbt. */
  def frameworks: Seq[Framework]

  /** A mapping from class name to instantiated test object. */
  def tests: Map[String, AnyRef]

  /** Actual main method of the test runner. */
  def testMain(args: Array[String]): Unit = Zone { implicit z =>
    val server_port = args.headOption.map(_.toInt).getOrElse(9000).toUShort
    val listen_sock = setupServer(server_port)

    onClient(listen_sock) { client_socket =>
      // We don't need to listen for incoming connections anymore
      unistd.close(listen_sock)
      testRunner(Array.empty, null, client_socket)
    }
  }

  /** Executes body, calls `perror` with `str` if the result is non-zero. */
  private def exitOnFailure(str: String)(body: => CInt)(
      implicit zone: Zone): Unit = {
    val err = body
    if (err != 0) {
      stdio.perror(toCString(str))
      scala.sys.exit(err)
    }
  }

  /** Sets up the server socket, returns the file descriptor. */
  private def setupServer(port: uint16_t)(implicit zone: Zone): CInt = {
    val server_address = native.alloc[sockaddr_in]
    server_address.sin_family = socket.AF_INET.toUShort
    server_address.sin_port = htons(port)
    server_address.sin_addr.in_addr = htonl(INADDR_ANY)

    val listen_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM, 0)
    if (listen_sock < 0) {
      scala.sys.error("Couldn't create listen socket.")
    }

    val on = stackalloc[CInt]
    !on = 1

    exitOnFailure("setsockopt") {
      socket.setsockopt(listen_sock,
                        socket.SOL_SOCKET,
                        socket.SO_REUSEADDR,
                        on.cast[Ptr[Byte]],
                        sizeof[CInt].toUInt)
    }

    exitOnFailure("bind") {
      socket.bind(listen_sock,
                  server_address.cast[Ptr[socket.sockaddr]],
                  sizeof[sockaddr_in].toUInt)
    }

    exitOnFailure("listen") {
      socket.listen(listen_sock, 16)
    }

    listen_sock
  }

  /** Waits for a connection on `socket`, executes `body` when a client connects.
   * The argument of `body` is the client socket descriptor. */
  private def onClient[T](socket: CInt)(body: CInt => T)(
      implicit zone: Zone): T = {
    val client_address     = native.alloc[sockaddr_in]
    val client_address_len = native.alloc[socklen_t]
    !client_address_len = sizeof[sockaddr_in].toUInt

    val client_socket =
      accept(socket, client_address.cast[Ptr[sockaddr]], client_address_len)

    if (client_socket < 0) {
      stdio.perror(toCString("accept"))
      scala.sys.exit(1)
    }

    body(client_socket)

  }

  /** Test runner loop.
   *
   * @param tasks         The tasks known to the runner (executed and waiting)
   * @param runner        The actual underlying `Runner`
   * @param client_socket The client socket from which we receive and reply to commands
   */
  @tailrec
  private def testRunner(tasks: Array[Task],
                         runner: Runner,
                         client_socket: CInt): Unit =
    receive(client_socket) match {
      case Command.NewRunner(id, args, remoteArgs) =>
        val runner = frameworks(id).runner(args.toArray,
                                           remoteArgs.toArray,
                                           new PreloadedClassLoader(tests))
        testRunner(tasks, runner, client_socket)

      case Command.SendInfo(id, None) =>
        val fps  = frameworks(id).fingerprints()
        val name = frameworks(id).name()
        val info = Command.SendInfo(id, Some(FrameworkInfo(name, fps.toSeq)))
        send(client_socket)(info)
        testRunner(tasks, runner, client_socket)

      case Command.Tasks(newTasks) =>
        val ts = runner.tasks(newTasks.toArray)
        val taskInfos = TaskInfos(ts.zipWithIndex.toSeq.map {
          case (t, id) => task2TaskInfo(id, t, runner)
        })
        send(client_socket)(taskInfos)
        testRunner(tasks ++ ts, runner, client_socket)

      case Command.Execute(taskID, colors) =>
        val handler = new RemoteEventHandler(client_socket)
        val loggers =
          colors.map(new RemoteLogger(client_socket, 0, _): Logger).toArray

        // Execute the task, possibly generating new tasks to execute...
        val newTasks = tasks
          .lift(taskID)
          .map(_.execute(handler, loggers))
          .getOrElse(Array.empty)
        val origSize = tasks.length

        // Convert the tasks to `TaskInfo` before sending to sbt. Keep task numbers correct.
        val taskInfos = newTasks.zipWithIndex.map {
          case (t, id) => task2TaskInfo(id + origSize, t, runner)
        }
        send(client_socket)(TaskInfos(taskInfos))
        testRunner(tasks ++ newTasks, runner, client_socket)

      case Command.RunnerDone(_) =>
        val r       = runner.done()
        val message = Command.RunnerDone(r)
        send(client_socket)(message)

      case other =>
        println(s"Unexpected message: $other")
    }

  private def task2TaskInfo(id: Int, task: Task, runner: Runner) =
    TaskInfo(id, task.taskDef, task.tags)

  /** Reads `len` bytes from `client` socket. */
  private def read(client: CInt)(len: Int): DataInputStream = {
    val buf = new Array[Byte](len)
    val _   = socket.recv(client, buf.asInstanceOf[ByteArray].at(0), len, 0)
    new DataInputStream(new ByteArrayInputStream(buf))
  }

  /** Receives a message from `client`. */
  private def receive[T](client: CInt): Message = {
    val msglen = read(client)(4).readInt()
    val in     = read(client)(msglen)
    val msgbuf = new Array[Byte](msglen)
    var total  = 0
    while (total < msglen) {
      total += in.read(msgbuf, total, msglen - total)
    }
    val deserializer = new SerializedInputStream(
      new ByteArrayInputStream(msgbuf))
    deserializer.readMessage()
  }

  /** Sends message `v` to `client`. */
  private def send[T](client: CInt)(msg: Message): Unit = {
    val bos = new ByteArrayOutputStream()
    SerializedOutputStream(new DataOutputStream(bos))(_.writeMessage(msg))
    val data = bos.toByteArray().asInstanceOf[ByteArray]

    var sent = 0
    while (sent < data.length) {
      sent += socket.send(client, data.at(sent), data.length - sent, 0).toInt
    }
  }

  private class RemoteEventHandler(client: CInt) extends EventHandler {
    override def handle(event: SbtEvent): Unit = {
      val ev = Event(event.fullyQualifiedName(),
                     event.fingerprint(),
                     event.selector(),
                     event.status(),
                     event.throwable(),
                     event.duration())
      send(client)(ev)
    }
  }

  private class RemoteLogger(client: CInt,
                             index: Int,
                             val ansiCodesSupported: Boolean)
      extends Logger {
    private def log(level: Log.Level,
                    msg: String,
                    twb: Option[Throwable]): Unit = {
      send(client)(Log(index, msg, twb, level))
    }

    override def error(msg: String): Unit  = log(Log.Level.Error, msg, None)
    override def warn(msg: String): Unit   = log(Log.Level.Warn, msg, None)
    override def info(msg: String): Unit   = log(Log.Level.Info, msg, None)
    override def debug(msg: String): Unit  = log(Log.Level.Debug, msg, None)
    override def trace(t: Throwable): Unit = log(Log.Level.Trace, "", Some(t))
  }
}
