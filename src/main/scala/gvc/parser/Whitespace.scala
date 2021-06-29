package gvc.parser
import fastparse._

trait Whitespace {
  val state: ParserState

  // Whitespace is a regular space, horizontal and vertical tab,
  // newline, formfeed and comments
  // Comments can be single-line (//) or multi-line (/* ... */)
  // Multi-line comments can be nested (/* ... /* ... */ ... */)
  implicit val whitespace = { implicit ctx: ParsingRun[_] => space }

  def space[_: P] = P(state.mode match {
    case DefaultMode => normalWhitespace.repX
    case MultiLineAnnotation => multiLineAnnotationWhitespace.repX
    case SingleLineAnnotation => singleLineAnnotationWhitespace.repX
  })

  def normalWhitespace[_: P] =
    P(normalWhitespaceChar | singleLineComment | multiLineComment)
  def singleLineAnnotationWhitespace[_: P] =
    P(singleLineAnnotationWhitespaceChar | singleLineComment | multiLineComment)
  def multiLineAnnotationWhitespace[_: P] =
    P(multiLineAnnotationWhitespaceChar | singleLineComment | multiLineComment)

  def normalWhitespaceChar[_: P] =
    P(CharIn(" \t\13\r\n"))
  def singleLineAnnotationWhitespaceChar[_: P] =
    P(CharIn(" \t\13\r@"))
  def multiLineAnnotationWhitespaceChar[_: P] =
    P(CharIn(" \t\r\n\13") | (!"@*/" ~~ "@"))

  def singleLineComment[_: P] = P("//" ~~ !"@" ~~/ (!"\n" ~~ AnyChar).repX ~~ ("\n" | End))

  def multiLineComment[_: P]: P[Unit] = P("/*" ~~ !"@" ~~/ (multiLineComment | (!"*/" ~~ AnyChar)).repX ~~ "*/")
}