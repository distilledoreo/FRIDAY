import {Schema} from 'prosemirror-model';
import {EditorState, Plugin} from 'prosemirror-state';
import {EditorView} from 'prosemirror-view';
import {history, undo, redo} from 'prosemirror-history';
import {baseKeymap, toggleMark, setBlockType} from 'prosemirror-commands';
import {keymap} from 'prosemirror-keymap';
import {tableNodes, tableEditing, addRowAfter, addColumnAfter, deleteRow, deleteColumn} from 'prosemirror-tables';

const id=()=>Array.from(crypto.getRandomValues(new Uint8Array(16)),n=>n.toString(16).padStart(2,'0')).join('');
const attrs={id:{default:null},kind:{default:'paragraph'},level:{default:1},order:{default:1}};
export const schema=new Schema({nodes:{
 doc:{content:'block+'},text:{group:'inline'},
 paragraph:{group:'block',content:'inline*',attrs,parseDOM:[{tag:'p'},{tag:'h1',getAttrs:()=>({kind:'heading',level:1})},{tag:'h2',getAttrs:()=>({kind:'heading',level:2})},{tag:'h3',getAttrs:()=>({kind:'heading',level:3})},{tag:'li',getAttrs:()=>({kind:'bullet'})},{tag:'blockquote',getAttrs:()=>({kind:'quote'})},{tag:'pre',getAttrs:()=>({kind:'code'})}],toDOM:n=>[n.attrs.kind==='heading'?'h'+n.attrs.level:n.attrs.kind==='quote'?'blockquote':n.attrs.kind==='code'?'pre':'p',{'data-kind':n.attrs.kind,'data-order':n.attrs.order},0]},
 image:{group:'block',atom:true,attrs:{id:{default:null},file:{default:''},alt:{default:''}},toDOM:n=>['div',{'class':'image-placeholder'},'Image: '+n.attrs.alt+' ('+n.attrs.file+')']},
 ...tableNodes({tableGroup:'block',cellContent:'paragraph+'}),
 table:{...tableNodes({tableGroup:'block',cellContent:'paragraph+'}).table,attrs:{id:{default:null}}}
},marks:{
 bold:{parseDOM:[{tag:'strong'},{tag:'b'}],toDOM:()=>['strong',0]},italic:{parseDOM:[{tag:'em'},{tag:'i'}],toDOM:()=>['em',0]},
 strike:{parseDOM:[{tag:'s'}],toDOM:()=>['s',0]},code:{parseDOM:[{tag:'code'}],toDOM:()=>['code',0]},
 link:{attrs:{href:{}},inclusive:false,parseDOM:[{tag:'a[href]',getAttrs:dom=>/^https?:\/\/|^mailto:|^assistant:\/\/artifact\//.test(dom.getAttribute('href'))?{href:dom.getAttribute('href')}:false}],toDOM:n=>['a',{href:n.attrs.href,rel:'noreferrer'},0]}
}});
// The portable format uses code-point offsets. ProseMirror/DOM positions use UTF-16.
export const points=(s,n)=>Array.from(s.slice(0,n)).length;
export const units=(s,n)=>Array.from(s).slice(0,n).join('').length;
export function toDoc(content){
 let number=0;
 return schema.node('doc',null,content.blocks.map(b=>{
  number=b.type==='ordered'?number+1:0;
  if(b.type==='table')return schema.node('table',{id:b.id},b.rows.map(row=>schema.node('table_row',null,row.map(c=>schema.node('table_cell',null,schema.node('paragraph',null,c?schema.text(c):null))))));
  if(b.type==='image')return schema.node('image',{id:b.id,file:b.file,alt:b.text});
  const chars=Array.from(b.text||''),cuts=[...new Set([0,chars.length,...(b.marks||[]).flatMap(m=>[m.from,m.to])])].sort((a,b)=>a-b),children=[];
  for(let i=0;i<cuts.length-1;i++){let a=cuts[i],z=cuts[i+1];if(z>a)children.push(schema.text(chars.slice(a,z).join(''),(b.marks||[]).filter(m=>m.from<=a&&m.to>=z).map(m=>schema.marks[m.type].create(m.type==='link'?{href:m.href}:null))));}
  return schema.node('paragraph',{id:b.id,kind:b.type,level:b.level||1,order:number||1},children);
 }));
}
let tableIds=[];
export function fromDoc(doc){
 const blocks=[];let tableIndex=0;
 doc.forEach(n=>{
  if(n.type.name==='image'){blocks.push({id:n.attrs.id||id(),type:'image',file:n.attrs.file,text:n.attrs.alt});return;}
  if(n.type.name==='table'){const rows=[];n.forEach(r=>{let row=[];r.forEach(c=>row.push(c.textContent));rows.push(row)});blocks.push({id:n.attrs.id||id(),type:'table',text:'',rows});tableIndex++;return;}
  let text='',marks=[];
  n.forEach(child=>{const start=Array.from(text).length;text+=child.text||'';const end=Array.from(text).length;child.marks.forEach(m=>{let last=marks.find(x=>x.type===m.type.name&&x.to===start&&x.href===m.attrs.href);if(last)last.to=end;else marks.push({type:m.type.name,from:start,to:end,...(m.attrs.href?{href:m.attrs.href}:{})});});});
  blocks.push({id:n.attrs.id||id(),type:n.attrs.kind||'paragraph',level:n.attrs.level||1,text,marks});
 });return {version:1,blocks};
}
const stableIds=new Plugin({appendTransaction(transactions,old,state){if(!transactions.some(t=>t.docChanged))return;let tr=state.tr,seen=new Set(),number=0;state.doc.forEach((n,pos)=>{if(['paragraph','image','table'].includes(n.type.name)){number=n.attrs.kind==='ordered'?number+1:0;let key=n.attrs.id;let fresh=!key||seen.has(key);if(fresh)key=id();if(fresh||n.type.name==='paragraph'&&n.attrs.order!==(number||1))tr.setNodeMarkup(pos,null,{...n.attrs,id:key,...(n.type.name==='paragraph'?{order:number||1}:{})});seen.add(key);}});return tr.docChanged?tr:null;}});
let view,channel;
const send=value=>{const message=JSON.stringify({...value,channel});if(window.FridayEditor)window.FridayEditor.post(message);else parent.postMessage(message,'*');};
function publish(){if(!view)return;const {$from,$to}=view.state.selection;let selection=null;if($from.parent===$to.parent&&$from.parent.type.name==='paragraph')selection={block:$from.parent.attrs.id,from:points($from.parent.textContent,$from.parentOffset),to:points($to.parent.textContent,$to.parentOffset),text:view.state.doc.textBetween($from.pos,$to.pos)};send({kind:'edit',content:fromDoc(view.state.doc),selection});}
export function start(content,session){
 channel=session;tableIds=content.blocks.filter(b=>b.type==='table').map(b=>b.id);
 if(view)view.destroy();
 view=new EditorView(document.querySelector('#editor'),{state:EditorState.create({doc:toDoc(content),plugins:[history(),keymap({'Mod-z':undo,'Mod-Shift-z':redo,'Mod-y':redo,'Mod-b':toggleMark(schema.marks.bold),'Mod-i':toggleMark(schema.marks.italic)}),keymap(baseKeymap),stableIds,tableEditing()]}),dispatchTransaction(tr){view.updateState(view.state.apply(tr));publish();},handleDOMEvents:{click:(_,e)=>{if(e.target.closest('a')){e.preventDefault();return true;}return false;}}});
 document.querySelector('#status').textContent='Select text, then use Ask FRIDAY or Add comment. Changes autosave to the computer.';
 publish();
}
window.fridayInit=(content,session)=>start(content,session);
window.addEventListener('message',e=>{if(e.source!==parent)return;try{const m=typeof e.data==='string'?JSON.parse(e.data):e.data;if(m.kind==='init')start(m.content,m.channel);}catch{}});
function command(run){if(!view)return;run(view.state,view.dispatch,view);view.focus();}
function ask(label){return new Promise(resolve=>{const dialog=document.createElement('dialog');const form=document.createElement('form');form.method='dialog';const input=document.createElement('input');input.placeholder=label;input.setAttribute('aria-label',label);input.style.width='min(70vw,500px)';const ok=document.createElement('button');ok.textContent='Insert';const cancel=document.createElement('button');cancel.textContent='Cancel';cancel.type='button';cancel.onclick=()=>dialog.close();form.append(document.createTextNode(label),document.createElement('br'),input,ok,cancel);dialog.append(form);document.body.append(dialog);let value=null;form.onsubmit=()=>{value=input.value};dialog.onclose=()=>{dialog.remove();resolve(value)};dialog.showModal();input.focus();});}
function button(label,run){const b=document.createElement('button');b.textContent=label;b.type='button';b.addEventListener('mousedown',e=>e.preventDefault());b.onclick=()=>command(run);document.querySelector('#toolbar').append(b);}
for(const [label,mark] of [['Bold','bold'],['Italic','italic'],['Strike','strike'],['Code','code']])button(label,toggleMark(schema.marks[mark]));
button('Undo',undo);button('Redo',redo);
for(const [label,kind,level] of [['Text','paragraph',1],['H1','heading',1],['H2','heading',2],['Bullet','bullet',1],['Number','ordered',1],['Quote','quote',1],['Code block','code',1]])button(label,(state,dispatch)=>{let tr=state.tr;state.doc.nodesBetween(state.selection.from,state.selection.to,(n,pos)=>{if(n.type.name==='paragraph')tr.setNodeMarkup(pos,null,{...n.attrs,kind,level});});dispatch(tr);});
button('Link',async(state,dispatch)=>{const href=await ask('Link URL (https://…)');if(href&&/^https?:\/\/|^mailto:|^assistant:\/\/artifact\//.test(href))toggleMark(schema.marks.link,{href})(state,dispatch);});
button('Table',(state,dispatch)=>{const c=()=>schema.node('table_cell',null,schema.node('paragraph'));dispatch(state.tr.replaceSelectionWith(schema.node('table',null,[schema.node('table_row',null,[c(),c()]),schema.node('table_row',null,[c(),c()])])));});
button('+ row',addRowAfter);button('+ column',addColumnAfter);button('− row',deleteRow);button('− column',deleteColumn);
button('Image',async(state,dispatch)=>{const file=await ask('Image file ID from Library');if(file&&/^[a-f0-9]{32}$/.test(file))dispatch(state.tr.replaceSelectionWith(schema.node('image',{id:id(),file,alt:await ask('Image description')||''})));});
window.addEventListener('error',e=>send({kind:'error',message:String(e.message).slice(0,1000)}));
send({kind:'ready'});
