package ammonite.sshd

import java.io._
import java.util.concurrent.TimeoutException

import ammonite.ops._
import com.jcraft.jsch.{Channel, JSch, Session, UserInfo}
import org.scalacheck.Gen

import scala.annotation.tailrec
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.Random

import scala.concurrent.ExecutionContext.Implicits.global

object SshTestingUtils {
  def alphaNumStr = Gen.listOf(Gen.alphaNumChar).map(_.mkString)
  def nonEmptyAlphaNumStr = Gen.nonEmptyListOf(Gen.alphaNumChar).map(_.mkString)

  lazy val testUsername = "test"
  lazy val testPassword = Random.alphanumeric.take(10).mkString
  lazy val testUser = (testUsername, testPassword)

  def genCreds = for {
    login ← nonEmptyAlphaNumStr
    password ← alphaNumStr
  } yield (login, password)

  def genCredsPair = for {
    creds1 ← genCreds
    creds2 ← genCreds
  } yield (creds1, creds2)

  def nonShellChannel = Gen.alphaStr.suchThat(_ != "shell")

  def genNonMatchingCredsPair = genCredsPair.suchThat(pair => pair._1 != pair._2)


  def withTmpDirectory[T](block: Path => T):T = {
    lazy val tmpDir = Path(Path.makeTmp)
    try {
      block(tmpDir)
    } finally {
      rm! tmpDir
    }
  }

  def testSshServer(user: (String, String), shell: ShellSession.Server)
                   (implicit dir: Path) = {
    def config = SshServerConfig(
      "localhost",
      port = 0,
      username = user._1,
      password = user._2,
      ammoniteHome = dir
    )
    SshServer(config, shell)
  }

  def withTestSshServer[T](user: (String, String), testShell: () => Any = () => ???)
                          (test: org.apache.sshd.SshServer => T)
                          (implicit dir: Path): T = {
    val server = testSshServer(user, (_, _) => testShell.apply())
    server.start()
    try {
      test(server)
    } finally {
      server.stop()
    }
  }

  def sshClient(creds: (String, String), sshServer: org.apache.sshd.SshServer): Session = {
    sshClient(creds, Option(sshServer.getHost).getOrElse("localhost"), sshServer.getPort)
  }

  def sshClient(creds: (String, String), host: String, port: Int): Session = {
    val client = new JSch
    val session = client.getSession(creds._1, host, port)
    session.setPassword(creds._2)
    session.setUserInfo(autoAcceptHostKeyUser(creds._2))
    session
  }

  private def autoAcceptHostKeyUser(password: String) = new UserInfo {
    override def promptPassword(message: String): Boolean = true
    override def promptPassphrase(message: String): Boolean = false
    override def promptYesNo(message: String): Boolean = true

    override def showMessage(message: String): Unit = { /*noop*/ }
    override def getPassword: String = password
    override def getPassphrase: String = ???
  }


  /* helper class for imitating user working with ssh shell session channel */
  class Shell private(shell: Channel) {
    def this(sshClient: Session) = this(sshClient.openChannel("shell"))

    val input = {
      val pipeEnd = new PipedInputStream(2048)
      val pipeStart = new PipedOutputStream(pipeEnd)
      shell.setInputStream(pipeEnd)

      new PrintStream(pipeStart, true)
    }
    val output = {
      val pipeEnd = new PipedInputStream(2048)
      val pipeStart = new PipedOutputStream(pipeEnd)
      shell.setOutputStream(pipeStart)

      new InputStreamReader(pipeEnd) {
        private val readBuffer = new Array[Char](1024)

        private def readNextChunk(): Option[String] = {
          val chunkSize = read(readBuffer, 0, readBuffer.length)
          if (chunkSize <= 0) None
          else {
            val chunk = new Array[Char](chunkSize)
            System.arraycopy(readBuffer, 0, chunk, 0, chunkSize)
            Some(new String(chunk))
          }
        }

        def readAll: String = {
          @tailrec def readMore(buffer: StringBuilder): StringBuilder = {
            readNextChunk() match {
              case Some(chunk) => readMore(buffer.append(chunk))
              case None => buffer
            }
          }
          readMore(new StringBuilder).toString()
        }
      }
    }

    def connect() = shell.connect()
    def isConnected = shell.isConnected
    def disconnect() = shell.disconnect()

    def awaitToBecomeDisconnected(duration: FiniteDuration) = {
      Await.result(becomeDisconnected(duration), duration)
    }

    // there is no callback that ssh client finished channel
    // due to ssh close session signal
    // so we wait for it to happen in bg thread
    def becomeDisconnected(duration: Duration): Future[Unit] = Future {
      def currentTime = System.currentTimeMillis()
      val startTime = currentTime
      def elapsedTime = (currentTime - startTime).millis
      @tailrec def awaitImpl():Unit = if (elapsedTime < duration) {
        if (isConnected) {
          Thread.sleep(100)
          awaitImpl()
        } else {
          /* exit loop */
        }
      } else {
        throw new TimeoutException("Timed out while waiting for channel to disconnect")
      }
      awaitImpl()
    }
  }
}
