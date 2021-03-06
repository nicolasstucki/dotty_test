package io.getquill.context

import scala.language.higherKinds
import scala.language.experimental.macros
import java.io.Closeable
import scala.compiletime.summonFrom
import scala.util.Try
import io.getquill.{ ReturnAction }
import miniquill.dsl.EncodingDsl
import miniquill.quoter.Quoted
import miniquill.quoter.QueryMeta
import io.getquill.derived._
import miniquill.context.mirror.MirrorDecoders
import miniquill.context.mirror.Row
import miniquill.dsl.GenericDecoder
import miniquill.quoter.ScalarPlanter
import io.getquill.ast.Ast
import io.getquill.ast.ScalarTag
import io.getquill.idiom.Idiom
import io.getquill.ast.{Transform, QuotationTag}
import miniquill.quoter.QuotationLot
import miniquill.quoter.QuotedExpr
import miniquill.quoter.ScalarPlanterExpr
import io.getquill.idiom.ReifyStatement

import io.getquill._

object GetAst {
  import miniquill.parser._
  import scala.quoted._ // Expr.summon is actually from here
  import miniquill.quoter.ScalarPlanter
  import io.getquill.idiom.LoadNaming
  import io.getquill.util.LoadObject
  import miniquill.dsl.GenericEncoder
  import io.getquill.ast.External

  inline def apply[T](inline quoted: Quoted[Query[T]]): io.getquill.ast.Ast = ${ applyImpl('quoted) }
  def applyImpl[T: Type](quoted: Expr[Quoted[Query[T]]])(implicit qctx: QuoteContext): Expr[io.getquill.ast.Ast] = {
    import qctx.tasty.{Try => TTry,Type => TType, _}
    '{ $quoted.ast }
  }
}

object GetLifts {
  import miniquill.parser._
  import scala.quoted._ // Expr.summon is actually from here
  import miniquill.quoter.ScalarPlanter
  import io.getquill.idiom.LoadNaming
  import io.getquill.util.LoadObject
  import miniquill.dsl.GenericEncoder
  import io.getquill.ast.External

  inline def apply[T](inline quoted: Quoted[Query[T]]): List[miniquill.quoter.ScalarPlanter[_, _]] = ${ applyImpl('quoted) }
  def applyImpl[T: Type](quoted: Expr[Quoted[Query[T]]])(implicit qctx: QuoteContext): Expr[List[miniquill.quoter.ScalarPlanter[_, _]]] = {
    import qctx.tasty.{Try => TTry,Type => TType, _}
    '{ $quoted.lifts }
  }
}

object GetRuntimeQuotes {
  import miniquill.parser._
  import scala.quoted._ // Expr.summon is actually from here
  import miniquill.quoter.ScalarPlanter
  import io.getquill.idiom.LoadNaming
  import io.getquill.util.LoadObject
  import miniquill.dsl.GenericEncoder
  import io.getquill.ast.External

  inline def apply[T](inline quoted: Quoted[Query[T]]): List[miniquill.quoter.QuotationVase] = ${ applyImpl('quoted) }
  def applyImpl[T: Type](quoted: Expr[Quoted[Query[T]]])(implicit qctx: QuoteContext): Expr[List[miniquill.quoter.QuotationVase]] = {
    import qctx.tasty.{Try => TTry,Type => TType, _}
    '{ $quoted.runtimeQuotes }
  }
}

object StaticTranslationMacro {
  import miniquill.parser._
  import scala.quoted._ // Expr.summon is actually from here
  import miniquill.quoter.ScalarPlanter
  import io.getquill.idiom.LoadNaming
  import io.getquill.util.LoadObject
  import miniquill.dsl.GenericEncoder
  import io.getquill.ast.External

  // Process the AST during compile-time. Return `None` if that can't be done.
  private[getquill] def processAst[T: Type](astExpr: Expr[Ast], idiom: Idiom, naming: NamingStrategy)(using qctx: QuoteContext):Option[(String, List[External])] = {
    import io.getquill.ast.{CollectAst, QuotationTag}

    def noRuntimeQuotations(ast: Ast) =
      CollectAst.byType[QuotationTag](ast).isEmpty

    // val queryMeta = 
    //   Expr.summon(using '[QueryMeta])

    val unliftedAst = new Unlifter(using qctx).apply(astExpr)
    if (noRuntimeQuotations(unliftedAst)) {
      val expandedAst = Expander.static[T](unliftedAst) 
      val (ast, stmt) = idiom.translate(expandedAst)(using naming)
      val output =
        ReifyStatement(
          idiom.liftingPlaceholder,
          idiom.emptySetContainsToken,
          stmt,
          forProbing = false
        )
      Some(output)
    } else {
      None
    }
  }

  // Process compile-time lifts, return `None` if that can't be done.
  // liftExprs = Lifts that were put into planters during the quotation. They are
  // 're-planted' back into the PreparedStatement vars here.
  // matchingExternals = the matching placeholders (i.e 'lift tags') in the AST 
  // that contains the UUIDs of lifted elements. We check against list to make
  // sure that that only needed lifts are used and in the right order.
  private[getquill] def processLifts(liftExprs: Expr[List[ScalarPlanter[_, _]]], matchingExternals: List[External])(using qctx: QuoteContext): Option[List[Expr[ScalarPlanter[_, _]]]] = {
    val extractedEncodeables =
      liftExprs match {
        case ScalarPlanterExpr.UprootableList(lifts) =>
          // get all existing expressions that can be encoded
          Some(lifts.map(e => (e.uid, e)).toMap)
        case _ => 
          // TODO Maybe do ctx.error here to show the lifts to the user, if 'verbose mode' is enabled.
          // Try it out to see how it looks
          println("Lifts do meet compiletime criteria:\n"+liftExprs.show); 
          None
      }

    extractedEncodeables.map { encodeables => 
      matchingExternals.collect {
        case tag: ScalarTag =>
          encodeables.get(tag.uid) match {
            case Some(encodeable) => encodeable
            case None =>
              report.throwError(s"Invalid Transformations Encountered. Cannot find lift with ID: ${tag.uid}.")
              // TODO Throw an error here or attempt to resolve encoders during runtime?
              // maybe the user has hand-modified a quoted block and only the
              // lifts are modified with some runtime values?
              // If throwing an error (or maybe even not?) need to tell the user which lifted ids cannot be found
              // should probably add some info to the reifier to "highlight" the question marks that
              // cannot be plugged in. It would also be really nice to show the user which lift-statements
              // are wrong but that requires a bit more thought (maybe match them somehow into the original AST
              // from quotedRow via the UUID???)
          }
      }.map(_.plant) // todo dedupe here?
    }
  }

  def idiomAndNamingStatic[D <: Idiom, N <: NamingStrategy](using qctx: QuoteContext, dialectTpe:Type[D], namingType:Type[N]): Try[(Idiom, NamingStrategy)] =
    for {
      idiom <- LoadObject(dialectTpe)
      namingStrategy <- LoadNaming.static(namingType)
    } yield (idiom, namingStrategy)


  def apply[T: Type, D <: Idiom, N <: NamingStrategy](
    quotedRaw: Expr[Quoted[Query[T]]]
  )(using qctx:QuoteContext, dialectTpe:Type[D], namingType:Type[N]): Expr[Option[(String, List[ScalarPlanter[_, _]])]] = {
    import qctx.tasty.{Try => TTry,Type => TType, _}
    // NOTE Can disable if needed and make quoted = quotedRaw. See https://github.com/lampepfl/dotty/pull/8041 for detail
    val quoted = quotedRaw.unseal.underlyingArgument.seal

    import scala.util.{Success, Failure}
    idiomAndNamingStatic match {
      case Success(v) =>
      case Failure(f) => f.printStackTrace()
    }

    val tryStatic =
      for {
        (idiom, naming)          <- idiomAndNamingStatic.toOption
        // TODO (MAJOR) Really should plug quotedExpr into here because inlines are spliced back in but they are not properly recognized by QuotedExpr.uprootableOpt for some reason
        quotedExpr               <- QuotedExpr.uprootableOpt(quoted) 
        (queryString, externals) <- processAst[T](quotedExpr.ast, idiom, naming)
        encodedLifts             <- processLifts(quotedExpr.lifts, externals)
      } yield {
        println(
          "Compile Time Query Is: " + 
            (if (System.getProperty("quill.macro.log.pretty", "false") == "true") idiom.format(queryString)
            else queryString)
        )

        // What about a missing decoder?
        // need to make sure that that kind of error happens during compile time
        // (also need to propagate the line number, talk to Li Houyi about that)
        '{ (${Expr(queryString)}, ${Expr.ofList(encodedLifts)}) }
      }

    if (tryStatic.isEmpty)
      println("WARNING: Dynamic Query Detected: ")

    tryStatic match {
      case Some(value) => 
        '{ Option($value) }
      case None => 
        '{ None }
    }
  }
}
