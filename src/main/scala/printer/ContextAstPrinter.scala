package printer

import pprint.{PPrinter, Tree, Util}

import fansi.Str
import pprint.{ Renderer, Tree, Truncated }
import scala.quoted._
import printer.AstPrinter

class ContextAstPrinter(using qctx: QuoteContext) extends AstPrinter {
  import qctx.tasty.{Ident, Tree => TTree, _}
  //import scala.tasty.Reflection

  //new Reflection()

  //def iter(args: Tree*) = (args: _*).iterator

  val newHandlers: PartialFunction[Any, Tree] = {
    
    case Ident(value) => Tree.Apply("Ident", List(Tree.Literal("foo")).iterator)
    //case Select(qualifier, name) => Tree.Apply("Select", iter(treeify(qualifier), treeify(name)))
  }

  val handlers = newHandlers.orElse(super.additionalHandlers)

  override def additionalHandlers: PartialFunction[Any, Tree] = handlers
}

object ContextAstPrinter {
  def contextAstPrinter(using qctx: QuoteContext) = new ContextAstPrinter
}