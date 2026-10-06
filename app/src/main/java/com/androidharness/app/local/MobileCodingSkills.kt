package com.androidharness.app.local
import com.androidharness.app.skills.SkillStore
internal object MobileCodingSkills {
 fun prompt(task:String,skills:SkillStore):String {
  if(!HtmlTaskIntent.createsHtml(task))return ""
  val names=if(task.contains("menu",true))listOf("html-page","html-menu") else listOf("html-page")
  return names.mapNotNull {name -> skills.view(name).getOrNull()?.content?.let {raw -> runCatching {com.androidharness.app.skills.SkillParser.parse(raw).body}.getOrDefault(raw).let {"Skill $name: $it"}}}.joinToString("\n").take(850)
 }
}
