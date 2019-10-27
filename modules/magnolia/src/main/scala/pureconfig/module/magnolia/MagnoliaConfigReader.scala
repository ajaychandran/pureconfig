package pureconfig.module.magnolia

import scala.collection.mutable

import _root_.magnolia._
import pureconfig.ConfigReader.Result
import pureconfig._
import pureconfig.error.WrongSizeList
import pureconfig.generic.{ CoproductHint, ProductHint }

/**
 * An object containing Magnolia `combine` and `dispatch` methods to generate `ConfigReader` instances.
 */
object MagnoliaConfigReader {

  def combine[A](ctx: CaseClass[ConfigReader, A])(implicit hint: ProductHint[A]): ConfigReader[A] =
    if (ctx.typeName.full.startsWith("scala.Tuple")) combineTuple(ctx)
    else if (ctx.isValueClass) combineValueClass(ctx)
    else combineCaseClass(ctx)

  private def combineCaseClass[A](ctx: CaseClass[ConfigReader, A])(implicit hint: ProductHint[A]): ConfigReader[A] = new ConfigReader[A] {
    def from(cur: ConfigCursor): Result[A] = {
      cur.asObjectCursor.flatMap { objCur =>
        val (res, obj) = ctx.parameters.foldLeft[(ConfigReader.Result[mutable.Builder[Any, List[Any]]], ConfigObjectCursor)]((Right(List.newBuilder[Any]), objCur)) {
          case ((res, cur), param) =>
            val (paramRes, nextCur) = hint.from(cur, param.typeclass, param.label, param.default)
            (Result.zipWith(res, paramRes)(_ += _), nextCur)
        }

        res.right.flatMap(values => hint.bottom(obj).fold[Result[A]](Right(ctx.rawConstruct(values.result())))(Left.apply))
      }
    }
  }

  private def combineTuple[A: ProductHint](ctx: CaseClass[ConfigReader, A]): ConfigReader[A] = new ConfigReader[A] {
    def from(cur: ConfigCursor): Result[A] = {
      val collCur = cur.asListCursor.map(Right.apply).left.flatMap(failure =>
        cur.asObjectCursor.map(Left.apply).left.map(_ => failure))

      collCur.flatMap {
        case Left(objCur) => combineCaseClass(ctx).from(objCur)
        case Right(listCur) =>
          if (listCur.size != ctx.parameters.length) {
            cur.failed(WrongSizeList(ctx.parameters.length, listCur.size))
          } else {
            val fields = Result.sequence(ctx.parameters.zip(listCur.list).map {
              case (param, cur) => param.typeclass.from(cur)
            })
            fields.map(ctx.rawConstruct)
          }
      }
    }
  }

  private def combineValueClass[A](ctx: CaseClass[ConfigReader, A]): ConfigReader[A] = new ConfigReader[A] {
    def from(cur: ConfigCursor): Result[A] =
      ctx.constructMonadic[Result, Param[ConfigReader, A]#PType](_.typeclass.from(cur))
  }

  def dispatch[A](ctx: SealedTrait[ConfigReader, A])(implicit hint: CoproductHint[A]): ConfigReader[A] = new ConfigReader[A] {
    def from(cur: ConfigCursor): Result[A] = {
      ctx.subtypes.foldRight[Result[A]](Left(hint.noOptionFound(cur))) {
        case (subtype, rest) =>
          hint.from(cur, subtype.typeclass, subtype.typeName.short, rest)
      }
    }
  }
}