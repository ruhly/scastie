package com.olegych.scastie
package sbt

import instrumentation._

import api._
import ScalaTargetType._

import scala.meta.parsers.Parsed

// import akka.util.Timeout
// import org.ensime.api._
// import org.ensime.config.EnsimeConfigProtocol
// import org.ensime.core.{Broadcaster, Project}
// import org.ensime.util.path._

import upickle.default.{read => uread, write => uwrite, Reader}

import akka.actor.{Actor, ActorRef}
// import akka.pattern.ask

import scala.concurrent._
import scala.concurrent.duration._
import java.util.concurrent.{TimeoutException, Callable, FutureTask, TimeUnit}

import scala.util.control.NonFatal

import org.slf4j.LoggerFactory

// import java.io.File
// import java.nio.charset.Charset

class SbtRunner(runTimeout: FiniteDuration, production: Boolean)
    extends Actor {
  private val defaultConfig = Inputs.default

  private var sbt = new Sbt(defaultConfig)
  private val log = LoggerFactory.getLogger(getClass)

  override def preStart(): Unit = warmUp()
  override def postStop(): Unit = sbt.exit()

  private def warmUp(): Unit = {
    if (production) {
      log.info("warming up sbt")
      val Right(in) = instrument(defaultConfig)
      sbt.eval("run", in, (line, _, _, _) => log.info(line), reload = false)
      ()
    }
  }

  private def instrument(
      inputs: Inputs
  ): Either[InstrumentationFailure, Inputs] = {
    if (inputs.worksheetMode && inputs.target.targetType != ScalaTargetType.Dotty) {
      instrumentation
        .Instrument(inputs.code, inputs.target)
        .map(instrumented => inputs.copy(code = instrumented))
    } else Right(inputs)
  }

  private def run(snippetId: SnippetId,
                  inputs: Inputs,
                  ip: String,
                  login: Option[String],
                  progressActor: ActorRef,
                  snippetActor: ActorRef,
                  forcedProgramMode: Boolean) = {

    val scalaTargetType = inputs.target.targetType
    val isScalaJs = inputs.target.targetType == ScalaTargetType.JS

    def eval(command: String, reload: Boolean): Boolean =
      sbt.eval(command,
               inputs,
               processSbtOutput(
                 inputs.worksheetMode,
                 forcedProgramMode,
                 progressActor,
                 snippetId,
                 snippetActor,
                 isScalaJs
               ),
               reload)

    def timeout(duration: FiniteDuration): Boolean = {
      log.info(s"restarting sbt: $inputs")
      progressActor !
        SnippetProgress.default
          .copy(
            snippetId = Some(snippetId),
            timeout = true,
            done = true,
            compilationInfos = List(
              Problem(
                Error,
                line = None,
                message = s"timed out after $duration"
              )
            )
          )

      sbt.kill()
      sbt = new Sbt(defaultConfig)
      warmUp()
      true
    }

    log.info(s"== updating $snippetId ==")

    val sbtReloadTime = 40.seconds
    val reloadError =
      if (sbt.needsReload(inputs)) {
        withTimeout(sbtReloadTime)(eval("compile", reload = true))(
          timeout(sbtReloadTime)
        )
      } else false

    if (!reloadError) {
      log.info(s"== running $snippetId ==")

      withTimeout(runTimeout)({
        scalaTargetType match {
          case JVM | Dotty | Native | Typelevel => eval("run", reload = false)
          case JS => eval("fastOptJS", reload = false)
        }
      })(timeout(runTimeout))

      log.info(s"== done  $snippetId ==")
    }
  }

  def receive = {
    case SbtTask(snippetId, inputs, ip, login, progressActor) => {
      log.info("login: {}, ip: {} run {}", login, ip, inputs)

      instrument(inputs) match {
        case Right(inputs0) => {
          run(snippetId,
              inputs0,
              ip,
              login,
              progressActor,
              sender,
              forcedProgramMode = false)
        }
        case Left(error) => {
          def signalError(message: String, line: Option[Int]): Unit = {
            val progress =
              SnippetProgress.default
                .copy(
                  snippetId = Some(snippetId),
                  compilationInfos = List(Problem(Error, line, message))
                )

            progressActor ! progress
            sender ! progress
          }

          error match {
            case HasMainMethod => {
              run(snippetId,
                  inputs.copy(worksheetMode = false),
                  ip,
                  login,
                  progressActor,
                  sender,
                  forcedProgramMode = true)
            }
            case UnsupportedDialect =>
              signalError(
                "The worksheet mode does not support this Scala target",
                None
              )

            case ParsingError(Parsed.Error(pos, message, _)) => {
              val lineOffset = getLineOffset(worksheetMode = true)

              signalError(message, Some(pos.start.line + lineOffset))
            }

          }
        }
      }
    }
  }

  private def withTimeout[T](
      timeout: Duration
  )(block: ⇒ T)(onTimeout: => T): T = {
    val task = new FutureTask(new Callable[T]() { def call = block })
    val thread = new Thread(task)
    try {
      thread.start()
      task.get(timeout.toMillis, TimeUnit.MILLISECONDS)
    } catch {
      case e: TimeoutException ⇒ onTimeout
    } finally {
      if (thread.isAlive) thread.stop()
    }
  }

  private def getLineOffset(worksheetMode: Boolean): Int =
    if (worksheetMode) -2
    else 0

  private def processSbtOutput(
      worksheetMode: Boolean,
      forcedProgramMode: Boolean,
      progressActor: ActorRef,
      snippetId: SnippetId,
      snippetActor: ActorRef,
      isScalaJs: Boolean
  ): (String, Boolean, Boolean, Boolean) => Unit = {
    (line, done, sbtError, reload) =>
      {
        val lineOffset = getLineOffset(worksheetMode)

        val problems = extractProblems(line, lineOffset)
        val instrumentations =
          extract[List[api.Instrumentation]](line, report = true)
        val runtimeError = extractRuntimeError(line, lineOffset)
        val sbtOutput = extract[ConsoleOutput.SbtOutput](line)

        // sbt plugin is not loaded at this stage. we need to drop those messages
        val initializationMessages = List(
          "[info] Loading global plugins from",
          "[info] Loading project definition from",
          "[info] Set current project to scastie",
          "[info] Updating {file:",
          "[info] Done updating.",
          "[info] Resolving",
          "[error] Type error in expression"
        )

        val isSbtMessage =
          initializationMessages.exists(message => line.startsWith(message))

        val userOutput =
          if (problems.isEmpty
              && instrumentations.isEmpty
              && runtimeError.isEmpty
              && !done
              && !isSbtMessage
              && sbtOutput.isEmpty)
            Some(line)
          else None

        val (scalaJsContent, scalaJsSourceMapContent) =
          if (done && isScalaJs && problems.isEmpty) {
            (sbt.scalaJsContent(), sbt.scalaJsSourceMapContent())
          } else (None, None)

        val progress = SnippetProgress(
          snippetId = Some(snippetId),
          userOutput = userOutput,
          sbtOutput = if (isSbtMessage) Some(line) else sbtOutput.map(_.line),
          compilationInfos = problems.getOrElse(Nil),
          instrumentations = instrumentations.getOrElse(Nil),
          runtimeError = runtimeError,
          scalaJsContent = scalaJsContent,
          scalaJsSourceMapContent = scalaJsSourceMapContent.map(
            remapSourceMap(snippetId)
          ),
          done = (done && !reload) || sbtError,
          timeout = false,
          sbtError = sbtError,
          forcedProgramMode = forcedProgramMode
        )

        progressActor ! progress.copy(scalaJsContent = None,
                                      scalaJsSourceMapContent = None)
        snippetActor ! progress
      }
  }

  private def remapSourceMap(
      snippetId: SnippetId
  )(sourceMapRaw: String): String = {
    try {
      val sourceMap = uread[SourceMap](sourceMapRaw)

      val sourceMap0 =
        sourceMap.copy(
          sources = sourceMap.sources.map(
            source =>
              if (source.startsWith(ScalaTarget.Js.sourceUUID)) {
                val host =
                  if (production) "https://scastie.scala-lang.org"
                  else "http://localhost:9000"

                host + snippetId.url(ScalaTarget.Js.sourceFilename)
              } else source
          )
        )

      uwrite(sourceMap0)
    } catch {
      case e: Exception => {
        e.printStackTrace()
        sourceMapRaw
      }
    }
  }

  private def extractProblems(line: String,
                              lineOffset: Int): Option[List[Problem]] = {
    val problems = extract[List[Problem]](line)

    problems.map(
      _.map(problem => problem.copy(line = problem.line.map(_ + lineOffset)))
    )
  }

  def extractRuntimeError(line: String,
                          lineOffset: Int): Option[RuntimeError] = {
    extract[Option[RuntimeError]](line).flatMap(
      _.map(error => error.copy(line = error.line.map(_ + lineOffset)))
    )
  }

  private def extract[T: Reader](line: String,
                                 report: Boolean = false): Option[T] = {
    try { Some(uread[T](line)) } catch {
      case NonFatal(e: scala.MatchError) => {
        if (report) {
          println("---")
          println(line)
          println("---")
          e.printStackTrace()
          println("---")
        }

        None
      }
      case NonFatal(_) => None
    }
  }

  private[SbtRunner] case class SourceMap(
      version: Int,
      file: String,
      mappings: String,
      sources: List[String],
      names: List[String],
      lineCount: Int
  )
}
