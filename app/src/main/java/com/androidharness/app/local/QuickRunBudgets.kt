package com.androidharness.app.local
internal object QuickRunBudgets {
 fun output(limits:LocalModelLimits,tokens:Int):LocalModelLimits {
  require(tokens in 16..minOf(4096,limits.context-128)) {"Output must be 16 to ${minOf(4096,limits.context-128)} tokens."}
  return limits.copy(output=tokens,input=minOf(limits.input,limits.context-tokens)).also {it.validate()}
 }
}
