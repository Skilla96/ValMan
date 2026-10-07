import express from "express";
import bcrypt from "bcryptjs";
import jwt from "jsonwebtoken";
import Database from "better-sqlite3";
import OpenAI from "openai";
import fs from "node:fs";
import path from "node:path";
import multer from "multer";

const app = express();
app.disable("x-powered-by");
app.use(express.json({limit:"3mb"}));

const PORT = Number(process.env.PORT || 8787);
const DATA_DIR = process.env.DATA_DIR || path.resolve("./data");
const DB_PATH = process.env.DB_PATH || path.join(DATA_DIR,"valman.sqlite");
const UPLOAD_DIR = path.join(DATA_DIR,"uploads");
fs.mkdirSync(UPLOAD_DIR,{recursive:true});

const JWT_SECRET = process.env.JWT_SECRET || "CHANGE-ME-BEFORE-PRODUCTION";
const SETUP_CODE = process.env.VALMAN_SETUP_CODE || "";
const OPENAI_API_KEY = process.env.OPENAI_API_KEY || "";
const OPENAI_MODEL = process.env.OPENAI_MODEL || "gpt-5.5";
const OPENAI_TTS_MODEL = process.env.OPENAI_TTS_MODEL || "gpt-4o-mini-tts";
const OPENAI_TTS_VOICE = process.env.OPENAI_TTS_VOICE || "marin";
const VECTOR_STORE_ID = process.env.OPENAI_VECTOR_STORE_ID || "";
if(process.env.NODE_ENV==="production" && JWT_SECRET==="CHANGE-ME-BEFORE-PRODUCTION") throw new Error("Imposta JWT_SECRET prima di avviare ValMan in produzione");
const ai = OPENAI_API_KEY ? new OpenAI({apiKey:OPENAI_API_KEY}) : null;

const db = new Database(DB_PATH);
db.pragma("journal_mode = WAL");
db.pragma("foreign_keys = ON");
db.exec(`
CREATE TABLE IF NOT EXISTS users(
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  role TEXT NOT NULL,
  password_hash TEXT,
  enabled INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS records(
  collection TEXT NOT NULL,
  record_key TEXT NOT NULL,
  json TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  updated_by TEXT,
  PRIMARY KEY(collection,record_key)
);
CREATE TABLE IF NOT EXISTS audit(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id TEXT,
  action TEXT NOT NULL,
  entity TEXT,
  entity_id TEXT,
  created_at TEXT NOT NULL,
  meta TEXT
);
CREATE TABLE IF NOT EXISTS uploads(
  id TEXT PRIMARY KEY,
  filename TEXT NOT NULL,
  stored_name TEXT NOT NULL,
  mime TEXT,
  machine TEXT,
  title TEXT,
  uploaded_by TEXT,
  created_at TEXT NOT NULL,
  openai_file_id TEXT
);
`);

const ALLOWED_COLLECTIONS = new Set(["machines","faults","interventions","leaves","communications","handovers","documents","shifts","turnationImages","turnationOcr"]);
const now = () => new Date().toISOString();
const normalizeId = v => String(v||"").trim().toUpperCase().replace(/[^A-Z0-9_.-]/g,"").slice(0,64);
const safeRole = r => ["ADMIN","MECCANICO","ELETTRICO","LETTURA"].includes(String(r||"").toUpperCase()) ? String(r).toUpperCase() : "LETTURA";
const publicUser = row => row ? {id:row.id,name:row.name,role:row.role,enabled:!!row.enabled} : null;
const sign = row => jwt.sign({sub:row.id,role:row.role},JWT_SECRET,{expiresIn:"30d",issuer:"valman"});
function audit(userId,action,entity="",entityId="",meta={}){db.prepare("INSERT INTO audit(user_id,action,entity,entity_id,created_at,meta) VALUES(?,?,?,?,?,?)").run(userId||"",action,entity,entityId,now(),JSON.stringify(meta||{}));}

function auth(req,res,next){
  const h=String(req.headers.authorization||"");
  if(!h.startsWith("Bearer "))return res.status(401).json({error:"UNAUTHORIZED",message:"Accesso richiesto"});
  try{
    const p=jwt.verify(h.slice(7),JWT_SECRET,{issuer:"valman"});
    const u=db.prepare("SELECT * FROM users WHERE id=?").get(p.sub);
    if(!u||!u.enabled)return res.status(401).json({error:"UNAUTHORIZED",message:"Account non valido o disabilitato"});
    req.user=u;next();
  }catch(e){return res.status(401).json({error:"UNAUTHORIZED",message:"Sessione scaduta"});}
}
function adminOnly(req,res,next){if(req.user?.role!=="ADMIN")return res.status(403).json({error:"FORBIDDEN",message:"Solo amministratore"});next();}

app.get("/health",(req,res)=>res.json({ok:true,version:"0.5.0",ai:!!ai,model:ai?OPENAI_MODEL:null,users:db.prepare("SELECT COUNT(*) n FROM users").get().n}));

app.post("/api/bootstrap",async(req,res)=>{
  try{
    if(db.prepare("SELECT COUNT(*) n FROM users").get().n>0)return res.status(409).json({error:"ALREADY_BOOTSTRAPPED",message:"Il server è già inizializzato"});
    if(!SETUP_CODE||String(req.body.setupCode||"")!==SETUP_CODE)return res.status(403).json({error:"BAD_SETUP_CODE",message:"Codice setup non valido"});
    const id=normalizeId(req.body.id),name=String(req.body.name||"").trim().slice(0,120),password=String(req.body.password||"");
    if(id.length<2||!name||password.length<6)return res.status(400).json({error:"BAD_INPUT",message:"ID, nome e password non validi"});
    const t=now(),hash=await bcrypt.hash(password,12);
    db.prepare("INSERT INTO users(id,name,role,password_hash,enabled,created_at,updated_at) VALUES(?,?,?,?,1,?,?)").run(id,name,"ADMIN",hash,t,t);
    const u=db.prepare("SELECT * FROM users WHERE id=?").get(id);audit(id,"BOOTSTRAP","user",id);
    res.json({ok:true,user:publicUser(u),token:sign(u)});
  }catch(e){res.status(500).json({error:"BOOTSTRAP_ERROR",message:String(e.message||e)});}
});

app.post("/api/auth/login",async(req,res)=>{
  const id=normalizeId(req.body.id),password=String(req.body.password||"");
  const u=db.prepare("SELECT * FROM users WHERE id=?").get(id);
  if(!u||!u.enabled)return res.status(401).json({error:"BAD_LOGIN",message:"ID non valido o disabilitato"});
  if(!u.password_hash)return res.status(409).json({error:"PASSWORD_NOT_SET",message:"Primo accesso: imposta la password"});
  if(!(await bcrypt.compare(password,u.password_hash)))return res.status(401).json({error:"BAD_LOGIN",message:"Password errata"});
  audit(id,"LOGIN","user",id);res.json({ok:true,user:publicUser(u),token:sign(u)});
});

app.post("/api/auth/claim",async(req,res)=>{
  const id=normalizeId(req.body.id),password=String(req.body.password||"");
  if(password.length<6)return res.status(400).json({error:"WEAK_PASSWORD",message:"Password minimo 6 caratteri"});
  const u=db.prepare("SELECT * FROM users WHERE id=?").get(id);
  if(!u||!u.enabled)return res.status(404).json({error:"UNKNOWN_ID",message:"ID non valido"});
  if(u.password_hash)return res.status(409).json({error:"ALREADY_CLAIMED",message:"Password già impostata"});
  const hash=await bcrypt.hash(password,12);db.prepare("UPDATE users SET password_hash=?,updated_at=? WHERE id=?").run(hash,now(),id);
  const fresh=db.prepare("SELECT * FROM users WHERE id=?").get(id);audit(id,"CLAIM","user",id);res.json({ok:true,user:publicUser(fresh),token:sign(fresh)});
});

app.get("/api/users",auth,(req,res)=>{
  const users=db.prepare("SELECT * FROM users ORDER BY name COLLATE NOCASE").all().map(publicUser);res.json({users});
});
app.post("/api/users",auth,adminOnly,(req,res)=>{
  const id=normalizeId(req.body.id),name=String(req.body.name||"").trim().slice(0,120),role=safeRole(req.body.role);
  if(id.length<2||!name)return res.status(400).json({error:"BAD_INPUT",message:"ID e nome obbligatori"});
  try{const t=now();db.prepare("INSERT INTO users(id,name,role,password_hash,enabled,created_at,updated_at) VALUES(?,?,?,NULL,1,?,?)").run(id,name,role,t,t);const u=db.prepare("SELECT * FROM users WHERE id=?").get(id);audit(req.user.id,"CREATE_USER","user",id,{role});res.json({ok:true,user:publicUser(u)});}catch(e){res.status(409).json({error:"USER_EXISTS",message:"ID già esistente"});}
});
app.patch("/api/users/:id",auth,adminOnly,(req,res)=>{
  const id=normalizeId(req.params.id),u=db.prepare("SELECT * FROM users WHERE id=?").get(id);if(!u)return res.status(404).json({error:"NOT_FOUND",message:"Utente non trovato"});
  if(id===req.user.id&&req.body.enabled===false)return res.status(400).json({error:"SELF_DISABLE",message:"Non puoi disabilitare il tuo account"});
  const enabled=req.body.enabled===undefined?!!u.enabled:!!req.body.enabled;const role=req.body.role?safeRole(req.body.role):u.role;const name=req.body.name?String(req.body.name).trim().slice(0,120):u.name;
  db.prepare("UPDATE users SET name=?,role=?,enabled=?,updated_at=? WHERE id=?").run(name,role,enabled?1:0,now(),id);audit(req.user.id,"UPDATE_USER","user",id,{enabled,role});res.json({ok:true,user:publicUser(db.prepare("SELECT * FROM users WHERE id=?").get(id))});
});

function recordKey(collection,o){
  if(collection==="shifts")return `${o.userId||""}|${o.date||""}`;
  return String(o.id||o.weekStart||"").trim();
}
function cleanRecord(collection,o){
  const copy={...o};delete copy.passwordHash;
  if(typeof copy.uri==="string"&&copy.uri.startsWith("content://"))copy.uri="";
  if(typeof copy.photoUri==="string"&&copy.photoUri.startsWith("content://"))copy.photoUri="";
  if(!copy.updatedAt)copy.updatedAt=now();
  return copy;
}
const upsertRecord=db.prepare(`INSERT INTO records(collection,record_key,json,updated_at,updated_by) VALUES(?,?,?,?,?)
ON CONFLICT(collection,record_key) DO UPDATE SET json=excluded.json,updated_at=excluded.updated_at,updated_by=excluded.updated_by
WHERE excluded.updated_at >= records.updated_at`);
function serverSnapshot(){
  const out={};for(const c of ALLOWED_COLLECTIONS)out[c]=[];
  for(const row of db.prepare("SELECT collection,json FROM records ORDER BY updated_at").all()){
    try{if(out[row.collection])out[row.collection].push(JSON.parse(row.json));}catch{}
  }
  out.users=db.prepare("SELECT * FROM users ORDER BY name COLLATE NOCASE").all().map(publicUser);return out;
}
app.post("/api/sync/exchange",auth,(req,res)=>{
  const snap=req.body?.snapshot&&typeof req.body.snapshot==="object"?req.body.snapshot:{};let changed=0;
  const tx=db.transaction(()=>{
    if(req.user.role==="ADMIN"&&Array.isArray(snap.users)){
      for(const raw of snap.users.slice(0,1000)){
        const id=normalizeId(raw?.id),name=String(raw?.name||"").trim().slice(0,120),role=safeRole(raw?.role),enabled=raw?.enabled===false?0:1;
        if(!id||!name||id===req.user.id)continue;
        const existing=db.prepare("SELECT * FROM users WHERE id=?").get(id);
        if(!existing){const t=now();db.prepare("INSERT INTO users(id,name,role,password_hash,enabled,created_at,updated_at) VALUES(?,?,?,NULL,?,?,?)").run(id,name,role,enabled,t,t);changed++;}
        else if(String(raw?.updatedAt||"") > String(existing.updated_at||"")){db.prepare("UPDATE users SET name=?,role=?,enabled=?,updated_at=? WHERE id=?").run(name,role,enabled,String(raw.updatedAt),id);}
      }
    }
    for(const [collection,items] of Object.entries(snap)){
      if(!ALLOWED_COLLECTIONS.has(collection)||!Array.isArray(items))continue;
      for(const raw of items.slice(0,10000)){
        if(!raw||typeof raw!=="object")continue;const o=cleanRecord(collection,raw),key=recordKey(collection,o);if(!key)continue;
        const info=upsertRecord.run(collection,key,JSON.stringify(o),String(o.updatedAt),req.user.id);changed+=info.changes;
      }
    }
  });tx();audit(req.user.id,"SYNC","workspace","",{changed});res.json({ok:true,changed,snapshot:serverSnapshot(),serverTime:now()});
});

const upload=multer({dest:UPLOAD_DIR,limits:{fileSize:50*1024*1024}});
app.post("/api/files",auth,upload.single("file"),async(req,res)=>{
  if(!req.file)return res.status(400).json({error:"FILE_REQUIRED",message:"File mancante"});
  const id=cryptoRandomId(),t=now(),title=String(req.body.title||req.file.originalname).slice(0,180),machine=String(req.body.machine||"").slice(0,120);
  let openaiFileId="";
  if(ai&&VECTOR_STORE_ID){
    try{const up=await ai.vectorStores.files.uploadAndPoll(VECTOR_STORE_ID,fs.createReadStream(req.file.path));openaiFileId=up.id||"";}catch(e){console.warn("vector upload failed",e.message);}
  }
  db.prepare("INSERT INTO uploads(id,filename,stored_name,mime,machine,title,uploaded_by,created_at,openai_file_id) VALUES(?,?,?,?,?,?,?,?,?)").run(id,req.file.originalname,req.file.filename,req.file.mimetype,machine,title,req.user.id,t,openaiFileId);
  audit(req.user.id,"UPLOAD","file",id,{title,machine});res.json({ok:true,file:{id,title,machine,mime:req.file.mimetype,createdAt:t,indexed:!!openaiFileId}});
});
function cryptoRandomId(){return Math.random().toString(36).slice(2,10).toUpperCase()+Date.now().toString(36).toUpperCase();}

function internalContext(query,user){
  const q=String(query||"").toLowerCase();const all=serverSnapshot();
  const machines=(all.machines||[]).map(x=>x.name).filter(Boolean);let machine="";
  for(const m of machines.sort((a,b)=>String(b).length-String(a).length)){const n=String(m).toLowerCase().replace(/\s+/g,"");if(q.replace(/\s+/g,"").includes(n)){machine=m;break;}}
  const words=q.replace(/[^a-z0-9à-ÿ]+/gi," ").split(/\s+/).filter(w=>w.length>3).slice(0,12);
  const score=o=>{const t=JSON.stringify(o).toLowerCase();let s=machine&&t.includes(String(machine).toLowerCase())?8:0;for(const w of words)if(t.includes(w))s++;return s;};
  const pick=(name,n=8)=>(all[name]||[]).map(o=>({o,s:score(o)})).filter(x=>x.s>0||(!machine&&name==="communications")).sort((a,b)=>b.s-a.s).slice(0,n).map(x=>x.o);
  const today=new Date().toISOString().slice(0,10);const shifts=(all.shifts||[]).filter(x=>x.userId===user.id&&x.date>=today).sort((a,b)=>String(a.date).localeCompare(String(b.date))).slice(0,10);
  return {machine,role:user.role,user:{id:user.id,name:user.name},faults:pick("faults"),interventions:pick("interventions",10),documents:pick("documents",6),handovers:pick("handovers",6),communications:pick("communications",5),leaves:pick("leaves",5),shifts};
}

const DOMAIN=`Sei Skilla Bot, assistente tecnico professionale per manutentori industriali.
AMBITO: meccanica, elettrica/elettronica, automazione, PLC per diagnosi/consultazione, azionamenti/inverter, idraulica, pneumatica, officina, saldatura, componenti, guasti, schemi, manuali, sicurezza e dati ValMan. Puoi fare piccola conversazione cordiale (saluti, come stai, grazie).
FUORI AMBITO: cucina, sport, gossip, intrattenimento e richieste non pertinenti. Rifiuta in una frase cordiale e riporta alla manutenzione.
METODO: comportati come un agente tecnico. Se il manutentore descrive un problema incompleto, fai UNA domanda diagnostica utile alla volta invece di fermarti. Mantieni il contesto. Distingui chiaramente: (1) dati/storico ValMan, (2) conoscenza tecnica generale, (3) fonti Web/manuali. Non inventare interventi, valori, codici o procedure.
MANUALI: per pairing, configurazioni, parametri o procedure di un dispositivo specifico chiedi marca/modello se mancano; privilegia manuale ufficiale del costruttore. Se trovi una fonte Web, cita nome documento/sezione e URL.
SICUREZZA: assistenza consultiva soltanto. Mai comandare PLC, gru, azionamenti o macchine, mai bypassare protezioni/interblocchi. Per attività con energia pericolosa richiama procedure aziendali, isolamento/LOTO e manuale del costruttore. Se una misura o procedura è incerta, dichiaralo.
VOCE: risposte iniziali brevi, naturali e concrete; approfondisci se richiesto.`;

function extractUrls(response){const urls=[];const walk=v=>{if(!v)return;if(Array.isArray(v)){for(const x of v)walk(x);return;}if(typeof v!=="object")return;if(v.type==="url_citation"&&v.url)urls.push(v.url);for(const value of Object.values(v))walk(value);};walk(response.output);return [...new Set(urls)].slice(0,6);}

app.post("/api/skilla",auth,async(req,res)=>{
  if(!ai)return res.status(503).json({error:"AI_NOT_CONFIGURED",message:"OPENAI_API_KEY non configurata sul server"});
  try{
    const query=String(req.body.query||"").trim();if(!query)return res.status(400).json({error:"QUERY_REQUIRED",message:"Richiesta vuota"});
    const internal=internalContext(query,req.user);
    const tools=[{type:"web_search"}];if(VECTOR_STORE_ID)tools.unshift({type:"file_search",vector_store_ids:[VECTOR_STORE_ID],max_num_results:6});
    const history=Array.isArray(req.body.history)?req.body.history.slice(-12):[];
    const response=await ai.responses.create({model:OPENAI_MODEL,instructions:DOMAIN,tools,input:`CONTESTO INTERNO VALMAN (fonte aziendale, non inventare oltre questi dati):\n${JSON.stringify(internal)}\n\nCONVERSAZIONE RECENTE:\n${JSON.stringify(history)}\n\nRICHIESTA DEL MANUTENTORE:\n${query}`});
    const urls=extractUrls(response);const source=urls.length?urls.join(" • "):(internal.faults.length||internal.interventions.length||internal.documents.length?"Dati ValMan + conoscenza tecnica":"Conoscenza tecnica Skilla Bot");
    audit(req.user.id,"AI_QUERY","assistant",internal.machine||"",{webSources:urls.length});res.json({answer:response.output_text||"",source,internalMachine:internal.machine});
  }catch(e){res.status(500).json({error:"SKILLA_ERROR",message:String(e.message||e)});}
});

app.post("/api/tts",auth,async(req,res)=>{
  if(!ai)return res.status(503).json({error:"AI_NOT_CONFIGURED",message:"OPENAI_API_KEY non configurata"});
  try{
    const text=String(req.body.text||"").trim().slice(0,3500);if(!text)return res.status(400).json({error:"TEXT_REQUIRED"});
    const speech=await ai.audio.speech.create({model:OPENAI_TTS_MODEL,voice:OPENAI_TTS_VOICE,input:text,instructions:"Parla in italiano naturale, professionale e cordiale. Sembri un collega esperto di manutenzione: ritmo naturale, niente tono da centralino, niente enfasi teatrale.",response_format:"mp3"});
    const audio=Buffer.from(await speech.arrayBuffer());res.set({"content-type":"audio/mpeg","cache-control":"no-store","content-length":String(audio.length)});res.send(audio);
  }catch(e){res.status(500).json({error:"TTS_ERROR",message:String(e.message||e)});}
});

app.get("/api/audit",auth,adminOnly,(req,res)=>{res.json({audit:db.prepare("SELECT * FROM audit ORDER BY id DESC LIMIT 300").all()});});

app.use((req,res)=>res.status(404).json({error:"NOT_FOUND",message:"Endpoint non trovato"}));
app.listen(PORT,"0.0.0.0",()=>console.log(`ValMan backend v0.5 on :${PORT} • DB ${DB_PATH}`));
