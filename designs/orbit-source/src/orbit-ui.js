
(() => {
 const root = document.getElementById('orbit-design-study');
 const device = root.querySelector('.orbit-device');
 const concepts = [
  {id:'graphite',number:'02',name:'深空专业',title:'深空 · 专注于创造',en:'GRAPHITE PRO',property:'右侧快捷键 / 深空铝 / 夜间工作',swatch:'linear-gradient(140deg,#565a63,#151619)',pad:'Stay in flow.',label:'PRECISION TRACKPAD'},
  {id:'titanium',number:'05',name:'双手操控',title:'钛金 · 双手的默契',en:'TITANIUM DUO',property:'双侧拇指区 / 温润钛色 / 实体键感',swatch:'linear-gradient(140deg,#e6e0d5,#ac9e8b)',pad:'一触，心手相连',label:'MULTI-TOUCH'}
 ];
 const apps = [
  {id:'gpt',name:'ChatGPT',icon:'gpt'}, {id:'claude',name:'Claude',icon:'claude'}, {id:'cursor',name:'Cursor',icon:'cursor'},
  {id:'finder',name:'Finder',icon:'finder'}, {id:'chrome',name:'Chrome',icon:'chrome'}, {id:'music',name:'QQ 音乐',icon:'music'},
  {id:'wechat',name:'微信',icon:'wechat'}, {id:'meeting',name:'腾讯会议',icon:'meeting'}, {id:'feishu',name:'飞书',icon:'feishu'}
 ];
 const state = {design:root.dataset.initialDesign || 'graphite',app:'cursor',mode:'coding',volume:65,brightness:75,sensitivity:55,natural:true,haptics:true,expanded:false,draft:'',recording:false,playing:false,muted:false};
 let toastTimer, priorFocus, recordPointer, startPoint, heldTimer, spaceHeld=false;
 const icon = (name) => `<i data-lucide="${name}" aria-hidden="true"></i>`;
 const esc = (value) => String(value).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
 const paintIcons = () => { if (globalThis.lucide) globalThis.lucide.createIcons({attrs:{width:17,height:17}}); };
 function appIcon(app) {
  const logos = {gpt:`<span class="gpt-rosette">${[0,60,120,180,240,300].map(d=>`<b style="transform:rotate(${d}deg)"></b>`).join('')}</span>`,claude:'✳',cursor:'<span class="cursor-crystal"></span>',finder:'<span class="finder-face">⌣</span>',chrome:'<span class="chrome-logo"></span>',music:icon('music-2'),wechat:icon('messages-square'),meeting:icon('video'),feishu:icon('send')};
  return `<span class="app-icon ${app.icon}">${logos[app.icon]}</span>`;
 }
 function appButtons() { return apps.map(a=>`<button type="button" class="app-button" data-app="${a.id}" aria-pressed="${state.app===a.id}" aria-label="切换到 ${a.name}">${appIcon(a)}<span class="app-name">${a.name}</span></button>`).join(''); }
 const keyData = [
  ['esc','Esc','','','退出'],['stop','停止','⌃C','stop','中断任务'],['yes','确认','Y','yes','确认 Y'],['up','↑','','not-focus','方向键 ↑'],['no','拒绝','N','','拒绝 N'],
  ['space','Space','','wide not-focus','空格'],['left','←','','not-focus','方向键 ←'],['down','↓','','not-focus','方向键 ↓'],['right','→','','not-focus','方向键 →'],
  ['cut','剪切','⌘X','edit-key not-focus','剪切 ⌘X'],['copy','复制','⌘C','edit-key','复制 ⌘C'],['paste','粘贴','⌘V','edit-key','粘贴 ⌘V'],['delete','删除','⌫','wide edit-key not-focus','删除']
 ];
 const dailyKeys = [
  ['esc','Esc','','','退出'],['close','关闭','⌘W','stop','关闭窗口'],['search','搜索','⌘F','yes','搜索'],['up','↑','','not-focus','方向键 ↑'],['switch','切换','⌘⇥','','切换窗口'],
  ['space','Space','','wide not-focus','空格'],['left','←','','not-focus','方向键 ←'],['down','↓','','not-focus','方向键 ↓'],['right','→','','not-focus','方向键 →'],
  ['cut','剪切','⌘X','edit-key not-focus','剪切 ⌘X'],['copy','复制','⌘C','edit-key','复制 ⌘C'],['paste','粘贴','⌘V','edit-key','粘贴 ⌘V'],['delete','删除','⌫','wide edit-key not-focus','删除']
 ];
 function keyButton(k) { return `<button type="button" class="key ${k[3]}" data-key="${k[0]}" data-command="${k[4]}" aria-label="${k[4]}"><span class="${['up','down','left','right'].includes(k[0])?'arrow-text':''}">${k[1]}</span>${k[2]?`<small>${k[2]}</small>`:''}</button>`; }
 function header() { return `<header class="systembar"><div class="system-left"><div class="orbit-brand"><span class="orbit-mark" aria-hidden="true"></span>Orbit</div><div class="mode-control" aria-label="快捷键模式"><button type="button" data-mode="coding" aria-pressed="${state.mode==='coding'}">Vibe Coding</button><button type="button" data-mode="daily" aria-pressed="${state.mode==='daily'}">日常</button></div></div><div class="system-right"><button type="button" class="connection" data-action="connection" aria-label="查看电脑连接状态"><span class="online-dot"></span>小西的 MacBook Pro <span class="connection-detail">· 6 ms</span></button><time class="time">09:41</time><span class="system-status"><span>86%</span><span class="battery-shape" aria-label="电量 86%"></span></span><button type="button" class="icon-button" data-action="settings" aria-label="设置">${icon('settings-2')}</button></div></header>`; }
 function appsPanel() { return `<section class="apps-panel o-panel" aria-label="常用 App"><div class="panel-heading"><h3>常用 App</h3><button type="button" class="icon-button" data-action="allapps" aria-label="查看所有应用">${icon('ellipsis')}</button></div><div class="app-grid">${appButtons()}</div><div class="left-extras"><h4>编辑与修饰键</h4><div class="edit-grid">${keyData.filter(k=>['cut','copy','paste','delete'].includes(k[0])).map(k=>keyButton([k[0],k[1],k[2],'',k[4]])).join('')}<button type="button" class="key" data-modifier="command" aria-pressed="false">⌘ <small>Command</small></button><button type="button" class="key" data-modifier="shift" aria-pressed="false">⇧ <small>Shift</small></button></div><div class="thumb-label">${icon('hand')}左手 · 应用与编辑</div></div></section>`; }
 function commandsPanel() { return `<section class="commands-panel o-panel" aria-label="快捷键"><div class="panel-heading"><h3 class="mode-label">${icon('command')}<span>${state.mode==='coding'?'Vibe Coding':'常用快捷键'}</span></h3><button type="button" class="icon-button" data-action="keys" aria-label="全部快捷键">${icon('sliders-horizontal')}</button></div><div class="keygrid">${(state.mode==='coding'?keyData:dailyKeys).map(keyButton).join('')}</div><button type="button" class="keymore icon-button" data-action="keys" aria-label="更多快捷键">${icon('ellipsis')}</button><div class="voicebar"><button type="button" class="talk" data-action="talk" aria-label="按住说话，演示语音状态">${icon('mic')}<span>按住说话</span></button><button type="button" class="send" data-action="send" aria-label="发送，长按换行"><span>发送</span>${icon('corner-down-left')}</button></div><div class="draft-note">${state.draft?'已有输入 · 点击发送':'松开发送到输入框 · 长按 ↵ 换行'}</div></section>`; }
 function pad() {
  const c=concepts.find(c=>c.id===state.design);
  return `<section class="trackpad" aria-label="触控区域"><div class="pad-top"><span class="pad-label">${c.label}</span><span class="pad-top-right">${icon('radio')}<span>已连接</span></span></div><button type="button" class="touch-surface" aria-label="触控板演示：拖动移动指针，轻点单击，滚轮模拟双指滚动"><span class="pad-center"><span class="pad-emblem">${icon('mouse-pointer-2')}</span><span class="pad-title">${c.pad}</span><span class="pad-subtitle">单指移动 · 双指滚动 · 三指切换</span></span><span class="pad-cursor">${icon('mouse-pointer-2')}</span></button><div class="pad-footer"><span class="pad-event"><span class="online-dot"></span><span class="event-text">${state.app==='cursor'?'Cursor':apps.find(a=>a.id===state.app).name} · 就绪</span></span><div class="pad-tools"><button type="button" class="icon-button" data-action="keyboard" aria-label="文字输入">${icon('keyboard')}</button><button type="button" class="icon-button" data-action="gestures" aria-label="手势指南">${icon('hand')}</button><button type="button" class="icon-button" data-action="expand" aria-label="${state.expanded?'退出沉浸触控':'展开触控区'}">${icon(state.expanded?'minimize-2':'maximize-2')}</button></div></div></section>`;
 }
 function tbButton(label,ico,action,main=false) { return `<button type="button" class="tb-btn ${main?'is-context':''}" data-action="${action}"${label?'':` aria-label="${action==='previous'?'上一首':action==='play'?(state.playing?'暂停':'播放'):action==='next'?'下一首':action==='mute'?'切换静音':action==='back'?'后退':action==='forward'?'前进':'刷新'}"`}>${ico?icon(ico):''}${label?`<span>${label}</span>`:''}</button>`; }
 function touchbar() {
  let items;
  if (state.app==='chrome') items=tbButton('','arrow-left','back')+tbButton('','arrow-right','forward')+tbButton('','rotate-cw','refresh')+tbButton('搜索或输入网址','search','address',true)+tbButton('收藏','star','bookmark');
  else if (state.app==='music') items=tbButton('','skip-back','previous')+tbButton('',state.playing?'pause':'play','play')+tbButton('','skip-forward','next')+tbButton('专注歌单 · QQ 音乐','music-2','playlist',true);
  else if (state.app==='finder') items=tbButton('新窗口','folder-plus','new-window')+tbButton('搜索文件','search','search-files',true)+tbButton('显示方式','layout-grid','view-files');
  else if (state.app==='meeting') items=tbButton('麦克风','mic','meeting-mic')+tbButton('摄像头','video','meeting-video')+tbButton('共享屏幕','screen-share','share-screen',true);
  else if (state.app==='cursor') items=tbButton('esc','','escape')+tbButton('新建对话','message-square-plus','new-chat',true)+tbButton('终端','terminal','terminal')+tbButton('运行','play','run');
  else items=tbButton('esc','','escape')+tbButton('新建对话','message-square-plus','new-chat',true)+tbButton('搜索','search','search')+tbButton('切换窗口','panels-top-left','switch-window');
  return `<section class="touchbar" aria-label="Mac Touch Bar"><span class="tb-caption">${icon('rectangle-horizontal')}TOUCH BAR</span><div class="tb-context">${items}</div><div class="tb-system"><label class="control-group" aria-label="屏幕亮度">${icon('sun')}<input type="range" min="0" max="100" value="${state.brightness}" data-range="brightness" class="mini-range" aria-label="屏幕亮度"></label><label class="control-group" aria-label="系统音量">${icon('volume-2')}<input type="range" min="0" max="100" value="${state.volume}" data-range="volume" class="mini-range" aria-label="系统音量"></label>${tbButton('',state.muted?'volume-x':'volume-1','mute')}</div></section>`;
 }
 function render() {
  const c=concepts.find(c=>c.id===state.design);
  root.querySelector('.design-switcher').innerHTML=concepts.map((c,i)=>`<button type="button" class="design-choice" data-design-select="${c.id}" aria-pressed="${c.id===state.design}" aria-label="方案 ${c.number}：${c.name}"><span class="design-swatch" style="background:${c.swatch}"></span><span><small>${c.number}</small><strong>${c.name}</strong></span></button>`).join('');
  root.querySelector('.design-heading h2').textContent=c.title;
  root.querySelector('.design-index').textContent=c.number;
  root.querySelector('.design-property').textContent=c.property;
  device.dataset.design=state.design;
  device.classList.toggle('is-expanded',state.expanded);
  device.innerHTML=header()+`<div class="workspace">${appsPanel()}${commandsPanel()}${pad()}${touchbar()}</div><div class="toast" role="status" aria-live="polite"></div><div class="modal-layer" hidden></div>`;
  paintIcons(); bindPad(); bindVoice();
 }
 function toast(message) {
  const t=device.querySelector('.toast'); if(!t) return;
  clearTimeout(toastTimer); t.innerHTML=icon('check')+`<span>${esc(message)}</span>`; t.classList.add('show'); paintIcons();
  toastTimer=setTimeout(()=>t.classList.remove('show'),1800);
 }
 function eventText(text) { const e=device.querySelector('.event-text'); if(e) e.textContent=text; }
 function updateApp(id) {
  state.app=id;
  device.querySelectorAll('[data-app]').forEach(b=>b.setAttribute('aria-pressed',b.dataset.app===id));
  device.querySelector('.touchbar').outerHTML=touchbar();
  eventText(`${apps.find(a=>a.id===id).name} · 就绪`);
  paintIcons(); toast(`已切换到 ${apps.find(a=>a.id===id).name}`);
 }
 function openDialog(title,content,subtitle='') {
  priorFocus=document.activeElement;
  const layer=device.querySelector('.modal-layer');
  layer.innerHTML=`<section class="o-dialog" role="dialog" aria-modal="true" aria-label="${esc(title)}"><div class="dialog-heading"><h3>${esc(title)}</h3><button type="button" class="icon-button" data-action="close-dialog" aria-label="关闭">${icon('x')}</button></div>${subtitle?`<p class="dialog-subtitle">${esc(subtitle)}</p>`:''}${content}</section>`;
  layer.hidden=false; device.querySelector('.systembar').inert=true; device.querySelector('.workspace').inert=true;
  paintIcons(); const first=layer.querySelector('textarea,input,button'); if(first) first.focus({preventScroll:true});
 }
 function closeDialog() { const layer=device.querySelector('.modal-layer'); layer.hidden=true; layer.innerHTML=''; device.querySelector('.systembar').inert=false; device.querySelector('.workspace').inert=false; if(priorFocus?.isConnected) priorFocus.focus({preventScroll:true}); }
 function settings() {
  openDialog('按你的习惯来',`<label class="setting-row"><span>指针速度<small data-value="sensitivity">${state.sensitivity}% · 精细控制</small></span><input type="range" min="10" max="100" value="${state.sensitivity}" data-range="sensitivity" aria-label="指针速度"></label><label class="setting-row"><span>自然滚动<small>内容跟随手指的移动方向</small></span><input type="checkbox" class="switch" data-setting="natural" ${state.natural?'checked':''}></label><label class="setting-row"><span>按键触感<small>轻触时提供反馈</small></span><input type="checkbox" class="switch" data-setting="haptics" ${state.haptics?'checked':''}></label><label class="setting-row"><span>亮度<small data-value="brightness">${state.brightness}%</small></span><input type="range" min="0" max="100" value="${state.brightness}" data-range="brightness" aria-label="亮度"></label><button type="button" class="dialog-primary" data-action="close-dialog">完成</button>`,'触控、手势与显示');
 }
 function gestures() {
  const rows=[['mouse-pointer-2','单指移动','在触控区拖动，移动电脑指针'],['hand','双指滚动','两指上下滑动，或用鼠标滚轮体验'],['layers','三指切换','三指水平滑动，切换工作空间'],['mouse','轻点与右键','轻点单击 · 双指轻点右键']];
  openDialog('顺手，就好',rows.map(r=>`<div class="gesture-row"><span class="gesture-icon">${icon(r[0])}</span><div><strong>${r[1]}</strong><small>${r[2]}</small></div></div>`).join('')+`<button type="button" class="dialog-primary" data-action="close-dialog">开始触控</button>`);
 }
 function executeKey(button) {
  button.classList.add('key-flash'); setTimeout(()=>button.classList.remove('key-flash'),180);
  const modifiers=Array.from(device.querySelectorAll('[data-modifier][aria-pressed="true"]')).map(b=>b.dataset.modifier==='command'?'⌘':'⇧').join('');
  eventText(`${modifiers}${button.dataset.command}`); toast(`⌨ ${modifiers}${button.dataset.command}`);
 }
 function bindPad() {
  const surface=device.querySelector('.touch-surface'), cursor=surface.querySelector('.pad-cursor');
  const points=new Map(); let gestureStart=null; let pointerX=surface.clientWidth*.53,pointerY=surface.clientHeight*.5;
  function ripple(x,y) { const el=document.createElement('span'); el.className='pad-ripple'; el.style.left=x+'px';el.style.top=y+'px';surface.append(el);setTimeout(()=>el.remove(),680); }
  surface.addEventListener('pointerdown',e=>{
   const rect=surface.getBoundingClientRect(); points.set(e.pointerId,{x:e.clientX,y:e.clientY});
   surface.setPointerCapture(e.pointerId); startPoint={x:e.clientX,y:e.clientY,time:Date.now(),count:points.size};
   if(points.size>1) { gestureStart={x:e.clientX,y:e.clientY,count:points.size}; }
   pointerX=e.clientX-rect.left;pointerY=e.clientY-rect.top;cursor.style.opacity='1';cursor.style.left=pointerX+'px';cursor.style.top=pointerY+'px';
   surface.classList.add('is-touching'); ripple(pointerX,pointerY); eventText(points.size>1?`${points.size} 指手势`:'正在移动指针');
  });
  surface.addEventListener('pointermove',e=>{
   if(!points.has(e.pointerId)) return;
   const last=points.get(e.pointerId); const dx=e.clientX-last.x,dy=e.clientY-last.y;
   if(points.size===1) { const speed=.4+state.sensitivity/60;pointerX=Math.max(0,Math.min(surface.clientWidth-20,pointerX+dx*speed));pointerY=Math.max(0,Math.min(surface.clientHeight-20,pointerY+dy*speed));cursor.style.left=pointerX+'px';cursor.style.top=pointerY+'px'; }
   else eventText(points.size===2?`双指滚动 ${dy>0?'↓':'↑'}`:`三指切换 ${dx>0?'→':'←'}`);
   points.set(e.pointerId,{x:e.clientX,y:e.clientY});
  });
  function end(e) {
   if(!points.has(e.pointerId))return;
   const count=gestureStart?.count||points.size;points.delete(e.pointerId);
   if(points.size===0) {
    surface.classList.remove('is-touching');
    const dist=startPoint?Math.hypot(e.clientX-startPoint.x,e.clientY-startPoint.y):100;
    if(count===3){toast('切换工作空间');eventText('三指切换');}
    else if(count===2 && dist<12){toast('右键菜单');eventText('双指轻点 · 右键');}
    else if(dist<7&&startPoint&&Date.now()-startPoint.time<450){eventText('轻点 · 单击');ripple(pointerX,pointerY);}
    else eventText(`${apps.find(a=>a.id===state.app).name} · 就绪`);
    gestureStart=null;
   }
  }
  surface.addEventListener('pointerup',end);
  surface.addEventListener('pointercancel',e=>{points.delete(e.pointerId); if(!points.size)surface.classList.remove('is-touching');});
  surface.addEventListener('wheel',e=>{e.preventDefault();const down=state.natural?e.deltaY>0:e.deltaY<0;eventText(`双指滚动 ${down?'↓':'↑'}`);},{passive:false});
  surface.addEventListener('contextmenu',e=>{e.preventDefault();eventText('右键菜单');toast('右键 · 已触发');});
  surface.addEventListener('click',e=>{if(e.detail===0){eventText('轻点 · 单击');ripple(surface.clientWidth/2,surface.clientHeight/2);}});
 }
 function setRecording(active,cancelled=false) {
  const b=device.querySelector('.talk');if(!b)return;
  state.recording=active;b.classList.toggle('recording',active);b.innerHTML=icon(active?'audio-lines':'mic')+`<span>${active?'松开结束':'按住说话'}</span>`;
  if(active) eventText('语音演示 · 正在聆听');
  else if(!cancelled) {state.draft='把这个页面的间距再调整一下。';device.querySelector('.draft-note').textContent='演示输入已就绪 · 点击发送';eventText('已转为文字 · 等待发送');toast('演示语音已转为文字');}
  else eventText('已取消语音输入');
  paintIcons();
 }
 function bindVoice() {
  const talk=device.querySelector('.talk');
  talk.addEventListener('pointerdown',e=>{recordPointer=e.pointerId;talk.setPointerCapture(e.pointerId);setRecording(true);});
  talk.addEventListener('pointerup',e=>{if(recordPointer===e.pointerId){recordPointer=null;setRecording(false);}});
  talk.addEventListener('pointercancel',()=>{recordPointer=null;setRecording(false,true);});
  talk.addEventListener('keydown',e=>{if((e.key===' '||e.key==='Enter')&&!e.repeat){e.preventDefault();spaceHeld=true;setRecording(true);}});
  talk.addEventListener('keyup',e=>{if(spaceHeld&&(e.key===' '||e.key==='Enter')){e.preventDefault();spaceHeld=false;setRecording(false);}});
  talk.addEventListener('blur',()=>{if(state.recording){spaceHeld=false;setRecording(false,true);}});
  const send=device.querySelector('.send');
  let longSend=false;
  send.addEventListener('pointerdown',()=>{longSend=false;heldTimer=setTimeout(()=>{longSend=true;state.draft+='\n';toast('已插入换行');eventText('换行 ⇧↵');},600);});
  ['pointerup','pointerleave','pointercancel'].forEach(t=>send.addEventListener(t,()=>clearTimeout(heldTimer)));
  send.addEventListener('click',e=>{e.stopPropagation();if(longSend)return;eventText(state.draft?'文字已发送':'回车 ↵');toast(state.draft?'文字已发送到输入框（演示）':'回车 ↵');state.draft='';device.querySelector('.draft-note').textContent='松开发送到输入框 · 长按 ↵ 换行';});
 }
 root.addEventListener('click',e=>{
  const b=e.target.closest('button');
  if(!b){if(e.target.classList.contains('modal-layer'))closeDialog();return;}
  if(b.dataset.designSelect){state.design=b.dataset.designSelect;state.expanded=false;state.recording=false;render();root.querySelector(`[data-design-select="${state.design}"]`).focus({preventScroll:true});return;}
  if(b.dataset.app){updateApp(b.dataset.app);return;}
  if(b.dataset.mode){state.mode=b.dataset.mode;render();device.querySelector(`[data-mode="${state.mode}"]`).focus({preventScroll:true});return;}
  if(b.dataset.key){executeKey(b);return;}
  if(b.dataset.modifier){const active=b.getAttribute('aria-pressed')!=='true';b.setAttribute('aria-pressed',active);b.style.background=active?'var(--o-accent-soft)':'';toast(`${b.dataset.modifier==='command'?'Command':'Shift'} ${active?'已按住':'已松开'}`);return;}
  const action=b.dataset.action;if(!action)return;
  if(action==='settings')settings();
  else if(action==='close-dialog')closeDialog();
  else if(action==='gestures')gestures();
  else if(action==='keyboard')openDialog('发送文字',`<textarea class="text-input" aria-label="要发送给电脑的文字" placeholder="输入你想对 AI 说的话…">${esc(state.draft)}</textarea><button type="button" class="dialog-primary" data-action="send-text">发送到电脑</button>`,'在电脑当前的输入框继续工作');
  else if(action==='send-text'){state.draft=device.querySelector('.text-input').value;if(!state.draft.trim()){device.querySelector('.text-input').focus();return;}closeDialog();eventText('文字已发送');toast('文字已发送到输入框（演示）');state.draft='';}
  else if(action==='allapps')openDialog('你的常用 App',`<div class="app-grid">${appButtons()}</div><button type="button" class="dialog-primary" data-action="close-dialog">完成</button>`,'轻点应用，切换电脑窗口与 Touch Bar');
  else if(action==='keys')openDialog('顺手的快捷键',`<div class="dialog-keygrid">${(state.mode==='coding'?keyData:dailyKeys).map(k=>keyButton([k[0],k[1],k[2],'',k[4]])).join('')}</div>`,'轻点执行 · 可在设置中调整触感');
  else if(action==='connection')openDialog('已连接到 Mac',`<div class="device-card">${icon('laptop')}<div><strong>小西的 MacBook Pro</strong><small>本地网络 · 连接稳定</small></div><span class="online-dot"></span></div><div class="setting-row"><span>连接延迟</span><span>6 ms</span></div><div class="setting-row"><span>平板电量</span><span>86%</span></div><div class="setting-row"><span>Touch Bar</span><span>跟随当前 App</span></div><button type="button" class="dialog-primary" data-action="close-dialog">继续使用</button>`,'演示连接状态');
  else if(action==='expand'){state.expanded=!state.expanded;render();device.querySelector('[data-action="expand"]').focus({preventScroll:true});}
  else if(action==='play'){state.playing=!state.playing;device.querySelector('.touchbar').outerHTML=touchbar();paintIcons();toast(state.playing?'正在播放 · 专注歌单':'已暂停');}
  else if(action==='mute'){state.muted=!state.muted;device.querySelector('.touchbar').outerHTML=touchbar();paintIcons();toast(state.muted?'已静音':'已恢复音量');}
  else if(action==='address')openDialog('搜索或输入网址',`<textarea class="text-input" aria-label="网址或搜索词" placeholder="输入网址或搜索词…"></textarea><button type="button" class="dialog-primary" data-action="send-text">在 Chrome 打开</button>`,'Touch Bar · Chrome');
  else if(action!=='talk'&&action!=='send'){
   const messages={'escape':'Esc','new-chat':'新建对话 ⌘L','terminal':'切换终端 ⌃`','run':'运行当前任务','back':'后退 ⌘[','forward':'前进 ⌘]','refresh':'刷新 ⌘R','bookmark':'收藏当前网页','previous':'上一首','next':'下一首','playlist':'专注歌单','new-window':'新建 Finder 窗口','search-files':'搜索文件','view-files':'切换文件视图','meeting-mic':'切换会议麦克风','meeting-video':'切换会议摄像头','share-screen':'共享屏幕','search':'搜索 ⌘F','switch-window':'切换窗口 ⌘⇥'};
   toast(messages[action]||action);eventText(messages[action]||action);
  }
 });
 root.addEventListener('input',e=>{
  if(e.target.dataset.range){const name=e.target.dataset.range;state[name]=Number(e.target.value);device.querySelectorAll(`[data-range="${name}"]`).forEach(x=>{if(x!==e.target)x.value=state[name];});const value=device.querySelector(`[data-value="${name}"]`);if(value)value.textContent=state[name]+'%';eventText(`${name==='volume'?'音量':name==='brightness'?'亮度':'指针速度'} ${state[name]}%`);}
 });
 root.addEventListener('change',e=>{if(e.target.dataset.setting){state[e.target.dataset.setting]=e.target.checked;}});
 root.addEventListener('keydown',e=>{
  const layer=device.querySelector('.modal-layer');
  if(!layer.hidden){
   if(e.key==='Escape'){e.preventDefault();closeDialog();}
   if(e.key==='Tab'){const focusables=Array.from(layer.querySelectorAll('button,input,textarea')).filter(x=>!x.disabled);const first=focusables[0],last=focusables[focusables.length-1];if(e.shiftKey&&document.activeElement===first){e.preventDefault();last.focus();}else if(!e.shiftKey&&document.activeElement===last){e.preventDefault();first.focus();}}
  }
 });
 render();
 if(globalThis.Tweak){
  const designOptions={direction:state.design};
  const tweak=new Tweak({container:device,onChange:()=>{state.design=designOptions.direction;state.expanded=false;render();}});
  tweak.addSelect(designOptions,'direction',{label:'设计方向',options:concepts.map(c=>({label:c.name,value:c.id}))});
 }
})();
