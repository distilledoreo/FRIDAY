const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const handlers = {}, shown = [], opened = [], posted = [];
let windows = [];
vm.runInNewContext(fs.readFileSync('web/src/jsMain/resources/notifications-sw.js', 'utf8'), {
  self: {addEventListener: (kind, handler) => handlers[kind] = handler,
    registration: {scope:'https://friday.example/friday/',showNotification:async (...args)=>shown.push(args)},
    clients: {matchAll:async()=>windows,openWindow:async url=>opened.push(url)}}
});
async function push(open='tasks') {
  let pending;
  handlers.push({data:{json:()=>({title:'Done',body:'Review',open,tag:'task'})},waitUntil:p=>pending=p});
  await pending;
}
async function click(destination) {
  let pending;
  handlers.notificationclick({notification:{data:{destination},close(){}},waitUntil:p=>pending=p});
  await pending;
}
(async()=>{
  await push(); assert.equal(shown.length,1);
  windows=[{visibilityState:'visible',focused:true}];
  await push(); assert.equal(shown.length,1);
  windows=[]; await push('https://evil.example/');
  assert.equal(shown[1][1].data.destination,'activity');
  await click('tasks'); assert.equal(opened[0],'https://friday.example/friday/#notice=tasks');
  windows=[{url:'https://friday.example/friday/',focus:async()=>{},postMessage:m=>posted.push(m)}];
  await click('activity'); assert.equal(posted[0].open,'activity'); assert.equal(opened.length,1);
  console.log('Service worker: background delivery, foreground suppression and safe notification navigation passed');
})().catch(error=>{console.error(error);process.exitCode=1});
