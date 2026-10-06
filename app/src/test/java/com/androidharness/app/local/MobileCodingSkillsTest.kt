package com.androidharness.app.local
import com.androidharness.app.skills.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
class MobileCodingSkillsTest {
 private fun content(name:String):String {
  val rel="src/main/assets/skills/$name/SKILL.md";val file=File(rel).takeIf {it.isFile} ?: File("app/$rel")
  return file.readText()
 }
 @Test fun menuSkillAndProductionSchemasFitMinimumContext() {
  val type=Class.forName("sun.misc.Unsafe");val field=type.getDeclaredField("theUnsafe").apply {isAccessible=true}
  val controller=type.getMethod("allocateInstance",Class::class.java).invoke(field.get(null),com.androidharness.app.browser.BrowserController::class.java) as com.androidharness.app.browser.BrowserController
  val tools=listOf(com.androidharness.app.tools.WriteFileTool(),com.androidharness.app.tools.ReadFileTool(),com.androidharness.app.tools.CreateDirTool(),com.androidharness.app.tools.BrowserNavigateTool(controller),com.androidharness.app.tools.BrowserGetDomTool(controller),com.androidharness.app.tools.BrowserGetLogsTool(controller))
  val schemas=LocalAgentProtocol.selectTools(tools.map {com.androidharness.app.llm.ToolSchema(it.name,it.description,it.parametersSchema)},com.androidharness.app.agent.AssistantMode.CODE)
  val bundled=listOf("html-page","html-menu").associateWith {SkillStore.BundledSkill(it,content(it))}
  val dir=java.nio.file.Files.createTempDirectory("menu-budget").toFile()
  try {
   val task="Create an Italian menu in single.html"
   val prompt=LocalAgentProtocol.systemPrompt("agent-folder",false,false,assistantMode=com.androidharness.app.agent.AssistantMode.CODE)+MobileCodingSkills.prompt(task,SkillStore(bundled,dir,{null},{emptySet()}))
   val history=LocalAgentProtocol.history(prompt,listOf(com.androidharness.app.core.ChatMessage(com.androidharness.app.core.Role.USER,task)),schemas,2304,k2Xml=true)
   assertTrue(history.sumOf {it.text.toByteArray().size+64}<=4608)
  } finally {dir.deleteRecursively()}
 }
 @Test fun bundledMenuGuidanceLoadsAutomaticallyAndRespectsDisable() {
  val bundled=listOf("html-page","html-menu").associateWith {SkillStore.BundledSkill(it,content(it))}
  val dir=java.nio.file.Files.createTempDirectory("coding-skills").toFile()
  try {
   val store=SkillStore(bundled,dir,{null},{emptySet()})
   val prompt=MobileCodingSkills.prompt("Create an Italian menu in HTML",store)
   assertTrue(prompt.contains("Skill html-page"));assertTrue(prompt.contains("Skill html-menu"));assertTrue(prompt.contains("individual euro prices"))
   assertEquals("",MobileCodingSkills.prompt("When was DevDay?",store))
   assertFalse(MobileCodingSkills.prompt("Create HTML menu",SkillStore(bundled,dir,{null},{setOf("html-menu")})).contains("Skill html-menu"))
  } finally {dir.deleteRecursively()}
 }
}
