package timelog_parser

import java.time.temporal.ChronoUnit
import java.time.{LocalDate, LocalDateTime, ZonedDateTime}
import java.util.concurrent.TimeUnit

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.util.Try
import scalaz.{ImmutableArray, ValidationNel, \/}
import scalaz.syntax.either._
import scalaz.syntax.traverse._
import scalaz.syntax.validation._
import scalaz.syntax.std.option._
import scalaz.std.string.stringSyntax._
import scalaz.syntax.applicative._
import scalaz.std.vector._
import scalaz.effect._
import scalaz.stream._

import scala.util.matching.Regex

object WorkflowDateRange {
  def create(start: ZonedDateTime, end: ZonedDateTime): String \/ WorkflowDateRange = {
    if (end isAfter start) new WorkflowDateRange(start, end).right
    else s"WorkflowDateRange cannot start ($start) after end ($end)!".left
  }
}
case class WorkflowDateRange private (start: ZonedDateTime, end: ZonedDateTime) {
  def toDuration = FiniteDuration(
    ChronoUnit.MILLIS.between(start, end), TimeUnit.MILLISECONDS
  )

  override def toString = s"WorkflowDateRange(${App.durationToSumString(toDuration)}h, $start - $end)"
}

sealed trait WorkEntry {
  def date: LocalDate
  def duration: FiniteDuration
}
object WorkEntry {
  case class ExactTime(range: WorkflowDateRange) extends WorkEntry {
    override def date = range.start.toLocalDate
    override def duration = range.toDuration

    override def toString = s"ExactTime(${App.durationToSumString(duration)}h, ${range.start} - ${range.end})"
  }
  case class YouTrack(date: LocalDate, duration: FiniteDuration) extends WorkEntry {
    override def toString = s"YouTrack (${App.durationToSumString(duration)}h, $date)"
  }
}

object App extends SafeApp {
  val DateTimeRe = """^.*(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})$""".r
  val JustNowRe = """.*just now$""".r
  val MinutesAgoRe = """.*(\d+) minutes ago$""".r
  val HoursAgoRe = """.*(\d+) hours ago$""".r
  val WorkRe = """^\s?Work: (.+?) (?:-|to) (.+)$""".r

  object DateMatcher {
    val DateRe = """^\d{2} \w{3} \d{4}$""".r
    val Date2Re = """^\d{4}-\d{2}-\d{2}$""".r

    def unapply(arg: String): Option[LocalDate] = {
      arg match {
        case DateRe() =>
          Some(LocalDate.parse(arg, Formats.DateFormat))
        case Date2Re() =>
          Some(LocalDate.parse(arg))
        case _ =>
          None
      }
    }
  }

  object TimeMatcher {
    val TimeRe = """^(?:(\d+) hours? ?)?(?:(\d+) min)?$""".r
    val Time2Re = """^(?:(\d+)h?)?(?:(\d+)m)?$""".r
    val OnlyHoursRe: Regex = """^(\d+) hours$""".r
    val OnlyMinutesRe: Regex = """^(\d+) min$""".r

    def unapply(arg: String): Option[(Int, Int)] = arg match {
      case TimeRe(hoursS, minutesS) => Some((hoursS.toInt, minutesS.toInt))
      case Time2Re(hoursS, minutesS) =>
        println(hoursS + minutesS)
        Some((hoursS.toInt, minutesS.toInt))
      case OnlyHoursRe(intS) => Some((intS.toInt, 0))
      case OnlyMinutesRe(intS) => Some((0, intS.toInt))
      case _ => None
    }
  }

  override def run(args: ImmutableArray[String]) = {
    for {
      lines <-
        if (args.isEmpty)
          IO.putStrLn("Waiting for input, !done\\n when finished\n")
            .flatMap(_ => readStdin)
        else
          readFile(args(0))
      workEntriesV = process(lines)
      _ <- workEntriesV.fold(
        errors => IO.putStrLn(s"\n\nErrors while parsing:\n\n${errors.list.toVector.mkString("\n")}"),
        workEntries => for {
          _ <- IO.putStrLn(workEntries.mkString("\n"))
          perDay = workedPerDay(workEntries)
          outLines = monthWorkLines(perDay)
          _ <- IO.putStrLn("===========")
          _ <- IO.putStrLn(outLines.mkString("\n"))
        } yield ()
      )
    } yield ()
  }

  def readSource(read: IO[String]): IO[Vector[String]] = {
    lazy val readAll: IO[Vector[String]] = read.flatMap {
      case "!done" | null => IO(Vector.empty)
      case line => readAll.map(line.trim +: _)
    }

    readAll
  }

  val readStdin = readSource(IO.readLn)
  def readFile(path: String) = IO(io.linesR(path).map(_.trim).runLog.run)

  def parseDate(s: String): String \/ ZonedDateTime =
    Formats.WorkEntryFormats.foldLeft(Option.empty[ZonedDateTime]) {
      case (None, format) =>
        Try {
          ZonedDateTime.parse(s, format)
        }.toOption
      case (o, _) => o
    }.toRightDisjunction(s"Can't parse $s as date time with ${Formats.WorkEntryFormats}")

  type Lines = Vector[String]
  type ProcessEntry = ValidationNel[String, WorkEntry]
  type ProcessResult = ValidationNel[String, Vector[WorkEntry]]

  sealed trait Parser {
    def apply(line: String): (Vector[ProcessEntry], Parser)
    def finish(): Vector[ProcessEntry]
  }
  case class HasDateParser(date: LocalDate) extends Parser {
    def apply(line: String) = {
      line match {
        case WorkRe(start, end) =>
          val entry = HasDateParser.matchWork(line, start, end)
          (Vector(entry), StartingParser)

        case TimeMatcher(hours, minutes) =>
          val duration =
            FiniteDuration(hours, TimeUnit.HOURS) + FiniteDuration(minutes, TimeUnit.MINUTES)

//          (Vector(WorkEntry.YouTrack(date, duration).successNel), StartingParser)
          (Vector.empty, HasTimeParser(WorkEntry.YouTrack(date, duration)))
        case _ =>
          (Vector.empty, this)
      }
    }

    override def finish() = Vector.empty
  }
  object HasDateParser {
    def matchWork(line: String, start: String, end: String) = {
      val startV = parseDate(start).validationNel
      val endV = parseDate(end).validationNel
      val entry =
        (startV |@| endV) { WorkflowDateRange.create }.fold(
          errs => errs.map(err => s"Error while parsing '$line' as work: $err").failure,
          either => either.validationNel
        ).map(WorkEntry.ExactTime.apply)
      entry
    }
  }
  case class HasTimeParser(entry: WorkEntry.YouTrack) extends Parser {
    def apply(line: String) = {
      line match {
        case WorkRe(start, end) =>
          val entry = HasDateParser.matchWork(line, start, end)
          (Vector(entry), StartingParser)
        case _ =>
          val (results, nextParser) = StartingParser(line)
          if (nextParser == StartingParser) {
            (results, this)
          }
          else {
            // Found a date
            (entry.successNel[String] +: results, nextParser)
          }
      }
    }

    override def finish() = Vector(entry.successNel)
  }
  object StartingParser extends Parser {
    def apply(line: String) = {
      line match {
        case DateMatcher(date) =>
          (Vector.empty, HasDateParser(date))

        case JustNowRe() =>
          val date = LocalDate.now()
          (Vector.empty, HasDateParser(date))

        case MinutesAgoRe(minutes) =>
          val dateTime = LocalDateTime.now().minus(minutes.toLong, ChronoUnit.MINUTES)
          (Vector.empty, HasDateParser(dateTime.toLocalDate))

        case HoursAgoRe(hours) =>
          val dateTime = LocalDateTime.now().minus(hours.toLong, ChronoUnit.HOURS)
          (Vector.empty, HasDateParser(dateTime.toLocalDate))

        case DateTimeRe(date) =>
          val dateTime = LocalDateTime.parse(date)
          (Vector.empty, HasDateParser(dateTime.toLocalDate))

        case _ =>
          (Vector.empty, this)
      }
    }

    override def finish() = Vector.empty
  }

  def process(lines: Lines): ProcessResult = {
    @tailrec def rec(
      lines: Lines, parser: Parser, current: ProcessResult
    ): ProcessResult = {
      if (lines.isEmpty) current +++ parser.finish().sequenceU
      else {
        val line = lines.head
        val rest = lines.tail
        val (results, nextParser) = parser(line)
        val sequencedResults = results.sequenceU
        rec(rest, nextParser, current +++ sequencedResults)
      }
    }

    rec(lines, StartingParser, Vector.empty[WorkEntry].successNel)
  }

  def workedPerDay(entries: Vector[WorkEntry]): Map[Int, FiniteDuration] =
    entries.groupBy(_.date.getDayOfMonth).mapValues(_.map(_.duration).reduce(_ + _))

  def monthWorkLines(perDay: Map[Int, FiniteDuration]): Vector[String] =
    (1 to 31).map { day =>
      perDay.get(day).fold("")(durationToSumString)
    }(collection.breakOut)

  def durationToSumString(duration: FiniteDuration): String = {
    val seconds = duration.toSeconds
    val hours = seconds / 60 / 60
    val minutes = (seconds - hours * 60 * 60) / 60
    val sum = hours + minutes.toDouble / 60
    "%.2f".formatLocal(java.util.Locale.US, sum)
  }
}
