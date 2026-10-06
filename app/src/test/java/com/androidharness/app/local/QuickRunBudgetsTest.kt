package com.androidharness.app.local
import org.junit.Assert.*
import org.junit.Test
class QuickRunBudgetsTest {
 @Test fun largerOutputReservesInputAndPreservesRuntimeOptions() {
  val old=LocalModelLimits(context=3072,input=2304,output=768,threads=3,kvCache=KvCacheQuantization.Q5_0,visionEnabled=true)
  val next=QuickRunBudgets.output(old,2048);assertEquals(1024,next.input);assertEquals(2048,next.output);assertEquals(old.threads,next.threads);assertEquals(old.kvCache,next.kvCache);assertTrue(next.visionEnabled)
 }
 @Test fun tooLargeOrInvalidBudgetIsRejected() {
  for(value in listOf(0,15,2945,4097))assertTrue(runCatching {QuickRunBudgets.output(LocalModelLimits(context=3072,input=2304,output=768),value)}.isFailure)
 }
}
