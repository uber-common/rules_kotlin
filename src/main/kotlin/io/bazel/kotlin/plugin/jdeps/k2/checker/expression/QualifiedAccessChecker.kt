package io.bazel.kotlin.plugin.jdeps.k2.checker.expression

import io.bazel.kotlin.plugin.jdeps.k2.ClassUsageRecorder
import io.bazel.kotlin.plugin.jdeps.k2.binaryClass
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirQualifiedAccessExpressionChecker
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.expressions.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.references.toResolvedFunctionSymbol
import org.jetbrains.kotlin.fir.types.isExtensionFunctionType
import org.jetbrains.kotlin.fir.types.isUnit
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.KtSourceElement

internal class QualifiedAccessChecker(
  private val classUsageRecorder: ClassUsageRecorder,
) : FirQualifiedAccessExpressionChecker(MppCheckerKind.Common) {
  context(CheckerContext, DiagnosticReporter)
  override fun check(
    expression: FirQualifiedAccessExpression,
  ) {
    // track function's owning class
    val resolvedCallableSymbol = expression.toResolvedCallableSymbol()
    resolvedCallableSymbol?.containerSource?.binaryClass()?.let {
      classUsageRecorder.recordClass(it)
    }

    // track return type
    val isExplicitReturnType: Boolean = expression is FirConstructor
    resolvedCallableSymbol?.resolvedReturnTypeRef?.let {
      classUsageRecorder.recordTypeRef(
        it,
        this@CheckerContext,
        isExplicit = isExplicitReturnType,
        collectTypeArguments = false,
      )
    }

    // type arguments
    resolvedCallableSymbol?.typeParameterSymbols?.forEach { typeParam ->
      typeParam.resolvedBounds.forEach { classUsageRecorder.recordTypeRef(it, this@CheckerContext) }
    }

    // track fun parameter types based on referenced function
    expression.calleeReference
      .toResolvedFunctionSymbol()
      ?.valueParameterSymbols
      ?.forEach { valueParam ->
        valueParam.resolvedReturnTypeRef.let {
          classUsageRecorder.recordTypeRef(it, this@CheckerContext, isExplicit = false)
        }
      }
    // track fun arguments actually passed
    (expression as? FirFunctionCall)?.arguments?.map { it.resolvedType }?.forEach {
      classUsageRecorder.recordConeType(it, this@CheckerContext, isExplicit = !it.isExtensionFunctionType)
    }

    // track dispatch receiver
    expression.dispatchReceiver?.resolvedType?.let {
      if (!it.isUnit) {
        classUsageRecorder.recordConeType(it, this@CheckerContext, isExplicit = false)
      }
    }

    // track android resources
    getResourceName(expression)?.let {
      classUsageRecorder.recordResource(it)
    }
  }

  // TODO: more robust way to extract resourceName
  private fun getResourceName(expression: FirQualifiedAccessExpression): String? {
    return (expression as? FirPropertyAccessExpression)
      ?.source
      ?.getElementTextInContextForDebug()
      ?.replace(Regex("\\s+"), "")
      ?.takeIf { (".R." in it || it.startsWith("R.")) && '{' !in it && '(' !in it && ':' !in it}
  }
}
