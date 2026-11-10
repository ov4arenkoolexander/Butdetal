/* BUTDETAL Journal: shared catalog audit trail + source-code history for app and tests. */
(function(){
'use strict';
const $=id=>document.getElementById(id);
let entries=[];
const esc=v=>String(v==null?'':v).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
function fmtDate(v){try{return new Date(v).toLocaleString('uk-UA',{dateStyle:'medium',timeStyle:'short'})}catch(e){return String(v||'—')}}
function recordPayload(row){const data=row&&row.data;return data&&typeof data==='object'?data:row||{}}
function summarizeRecord(row){
 const data=recordPayload(row);
 const name=data.mark||data.name||data.title||data.file_name||data.caption||row.record_id||'Запис каталогу';
 const parts=[];
 if(data.type)parts.push(data.type);
 if(data.dimensions)parts.push(data.dimensions);
 if(data.volume)parts.push('об’єм '+data.volume);
 if(data.concreteGrade)parts.push('бетон '+data.concreteGrade);
 if(data.rebar)parts.push('армування '+data.rebar);
 return String(name)+(parts.length?' • '+parts.join(' • '):'');
}
function dbEntry(row){
 const act=String(row.action||'').toUpperCase();
 const actionLabel=act==='INSERT'?'Додано':act==='UPDATE'?'Змінено':act==='DELETE'?'Видалено':act;
 const payload=row.new_data||row.old_data||{};
 const title=actionLabel+': '+summarizeRecord(payload);
 const detail={action:row.action,table:row.table_name,record_id:row.record_id,old_data:row.old_data,new_data:row.new_data};
 return {category:'catalog',date:row.changed_at||row.created_at,actor:row.actor_name||'Користувач',title,summary:'Каталог • '+(row.table_name||'запис'),details:detail,searchText:JSON.stringify(detail)};
}
function translateCommitMessage(raw,category){
 const source=String(raw||'').split('\n')[0].trim();
 const exact={
  'Build and publish BUTDETAL APK':'Збірка та публікація APK BUTDETAL',
  'Build debug APK':'Збірка тестового APK',
  'Update version':'Оновлення версії застосунку',
  'Bump version':'Підвищення версії застосунку',
  'Add study materials and journal':'Додано навчальні матеріали та журнал змін',
  'Add journal':'Додано журнал змін',
  'Fix tests':'Виправлено тести',
  'Update tests':'Оновлено тести',
  'Update app':'Оновлено застосунок',
  'Initial commit':'Початкове додавання проєкту'
 };
 if(exact[source])return exact[source];
 let translated=source;
 const rules=[
  [/\\b(add|added|adding)\\b/gi,'додано'],[/\\b(fix|fixed|fixes|bugfix)\\b/gi,'виправлено'],[/\\b(update|updated|updating)\\b/gi,'оновлено'],[/\\b(remove|removed|delete|deleted)\\b/gi,'видалено'],[/\\b(improve|improved|improvement)\\b/gi,'покращено'],[/\\b(build|release)\\b/gi,'збірка'],[/\\b(test|tests)\\b/gi,'тести'],[/\\b(app|application)\\b/gi,'застосунок'],[/\\b(catalog|catalogue)\\b/gi,'каталог'],[/\\b(journal|log)\\b/gi,'журнал'],[/\\b(materials?)\\b/gi,'матеріали'],[/\\b(buttons?)\\b/gi,'кнопки'],[/\\b(screen|view)\\b/gi,'екран'],[/\\b(sync|synchronization)\\b/gi,'синхронізація'],[/\\b(version)\\b/gi,'версія'],[/\\b(working)\\b/gi,'роботу'],[/\\b(and)\\b/gi,'та'],[/\\b(for)\\b/gi,'для'],[/\\b(with)\\b/gi,'з']
 ];
 rules.forEach(([pattern,replacement])=>translated=translated.replace(pattern,replacement));
 if(translated===source && /[a-z]/i.test(source))return 'Зміни: '+source;
 return translated;
}
function codeEntry(commit,category){
 const info=commit.commit||{};
 const author=(commit.author&&commit.author.login)||(info.author&&info.author.name)||(info.committer&&info.committer.name)||'Автор GitHub';
 const message=translateCommitMessage(info.message,category);
 return {category,date:(info.author&&info.author.date)||(info.committer&&info.committer.date),actor:author,title:message,summary:category==='tests'?'Тести • study.js':'Застосунок • код',details:{commit:commit.sha,message:info.message,author:author,url:commit.html_url},url:commit.html_url,searchText:JSON.stringify({message,author,sha:commit.sha})};
}
async function fetchGitHubPath(path,category){
 const url='https://api.github.com/repos/ov4arenkoolexander/Butdetal/commits?path='+encodeURIComponent(path)+'&per_page=18';
 const response=await fetch(url,{headers:{Accept:'application/vnd.github+json'}});
 if(!response.ok)throw Error('GitHub HTTP '+response.status);
 const data=await response.json();
 return Array.isArray(data)?data.map(c=>codeEntry(c,category)):[];
}
function categoryLabel(category){return category==='app'?'Застосунок':category==='tests'?'Тести':'Каталог'}
function render(){
 const target=$('journalList');if(!target)return;
 const category=$('journalFilter')?$('journalFilter').value:'all';
 const query=($('journalSearch')?$('journalSearch').value:'').trim().toLocaleLowerCase('uk-UA');
 const filtered=entries.filter(e=>(category==='all'||e.category===category)&&(!query||(e.title+' '+e.actor+' '+e.summary+' '+e.searchText).toLocaleLowerCase('uk-UA').includes(query)));
 if(!filtered.length){target.innerHTML='<div class="journal-empty">За цими умовами записів не знайдено.</div>';return}
 target.innerHTML=filtered.map(e=>'<article class="journal-entry"><div class="journal-entry-head"><div><h3>'+esc(e.title)+'</h3><div class="journal-meta">'+esc(e.summary)+' · <b>'+esc(e.actor)+'</b></div><time>'+esc(fmtDate(e.date))+'</time></div><span class="journal-badge">'+esc(categoryLabel(e.category))+'</span></div><details><summary>Деталі запису</summary><pre>'+esc(JSON.stringify(e.details,null,2))+'</pre>'+(e.url?'<a href="'+esc(e.url)+'" target="_blank" rel="noopener">Переглянути commit на GitHub</a>':'')+'</details></article>').join('');
}
window.filterJournal=render;
window.loadJournal=async function(){
 const status=$('journalStatus');if(status)status.textContent='Завантажую історію…';
 const s=typeof currentButdetalSession==='function'?currentButdetalSession():null;
 if(!s||!s.access_token){if(status)status.textContent='Увійди в BUTDETAL, щоб переглянути журнал.';return}
 const failures=[];
 const tasks=[
  (async()=>{const rows=await sbRequest('/rest/v1/change_log?select=id,table_name,record_id,action,actor_user_id,actor_name,changed_at,old_data,new_data&order=changed_at.desc&limit=150');return (Array.isArray(rows)?rows:[]).map(dbEntry)})(),
  fetchGitHubPath('app/src/main/assets/index.html','app'),
  fetchGitHubPath('app/src/main/java/ua/budivelnyk/ua/MainActivity.java','app'),
  fetchGitHubPath('app/build.gradle','app'),
  fetchGitHubPath('app/src/main/assets/study.js','tests')
 ];
 const settled=await Promise.allSettled(tasks);
 const all=[];
 settled.forEach((result,i)=>{if(result.status==='fulfilled'){all.push(...result.value)}else{failures.push((i===0?'Supabase журнал':i===4?'GitHub тести':'GitHub застосунок')+': '+(result.reason&&result.reason.message||result.reason))}});
 const unique=new Map();all.forEach(e=>{const key=e.category+'|'+(e.details&&e.details.commit||e.details&&e.details.record_id||e.date)+'|'+e.title;if(!unique.has(key))unique.set(key,e)});
 entries=[...unique.values()].sort((a,b)=>Date.parse(b.date||0)-Date.parse(a.date||0));
 if(status)status.textContent='Завантажено '+entries.length+' записів.'+(failures.length?' Частина джерел недоступна: '+failures.join(' · '):'');
 render();
};
document.addEventListener('DOMContentLoaded',()=>{if($('journalList'))window.loadJournal();});
})();