package com.skilla.valman;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

public class AppStore {
    private static final String PREF = "valman_store_v1";
    private static final String[] SYNC_COLLECTIONS = new String[]{"machines","faults","interventions","leaves","communications","handovers","documents","shifts"};
    private final SharedPreferences prefs;
    private Runnable changeListener;
    private boolean suppressChange = false;

    public AppStore(Context context) {
        prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        ensureBaseData();
        migrateSyncMetadata();
    }

    public void setChangeListener(Runnable r) { changeListener = r; }
    private void changed() { if (!suppressChange && changeListener != null) changeListener.run(); }

    private void ensureBaseData() {
        if (!prefs.contains("machines")) {
            JSONArray a = new JSONArray();
            a.put(machine("2B60", "Trafila", "Linea / cesoia"));
            a.put(machine("SAS 2", "Finitura", "Linea SAS"));
            a.put(machine("Forno 1", "Forni", "Forno"));
            saveArray("machines", a);
        }
        if (!prefs.contains("users")) saveArray("users", new JSONArray());
        if (!prefs.contains("faults")) saveArray("faults", new JSONArray());
        if (!prefs.contains("interventions")) saveArray("interventions", new JSONArray());
        if (!prefs.contains("leaves")) saveArray("leaves", new JSONArray());
        if (!prefs.contains("communications")) saveArray("communications", new JSONArray());
        if (!prefs.contains("handovers")) saveArray("handovers", new JSONArray());
        if (!prefs.contains("documents")) saveArray("documents", new JSONArray());
        if (!prefs.contains("shifts")) saveArray("shifts", new JSONArray());
    }

    private void migrateSyncMetadata() {
        suppressChange = true;
        try {
            for (String key : SYNC_COLLECTIONS) {
                JSONArray a = array(key); boolean dirty = false;
                for (int i=0;i<a.length();i++) {
                    JSONObject o=a.optJSONObject(i); if(o==null) continue;
                    if (!o.has("updatedAt")) {
                        try { o.put("updatedAt", isoNow()); dirty=true; } catch (Exception ignored) {}
                    }
                    if ("shifts".equals(key) && !o.has("id")) {
                        try { o.put("id", shiftKey(o.optString("userId"),o.optString("date"))); dirty=true; } catch (Exception ignored) {}
                    }
                }
                if(dirty) saveArray(key,a);
            }
        } finally { suppressChange = false; }
    }

    private JSONObject machine(String name, String area, String note) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", UUID.randomUUID().toString());
            o.put("name", name); o.put("area", area); o.put("note", note); touch(o);
        } catch (JSONException ignored) {}
        return o;
    }

    private void touch(JSONObject o) { try { o.put("updatedAt", isoNow()); } catch (JSONException ignored) {} }
    private String shiftKey(String userId,String date){ return (userId==null?"":userId)+"|"+(date==null?"":date); }

    public JSONArray array(String key) {
        try { return new JSONArray(prefs.getString(key, "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    public void saveArray(String key, JSONArray array) {
        prefs.edit().putString(key, array.toString()).apply(); changed();
    }

    public boolean hasAdmin() {
        JSONArray a = array("users");
        for (int i=0;i<a.length();i++) { JSONObject u=a.optJSONObject(i); if(u!=null&&"ADMIN".equals(u.optString("role"))) return true; }
        return false;
    }

    public JSONObject addUser(String id, String name, String role, String password) {
        JSONArray a = array("users"); JSONObject o = new JSONObject();
        try {
            o.put("id", id.trim().toUpperCase(Locale.ITALY)); o.put("name", name.trim()); o.put("role", role);
            o.put("passwordHash", password==null||password.isEmpty()?"":hash(password)); o.put("enabled", true); touch(o);
            a.put(o); saveArray("users", a);
        } catch (JSONException ignored) {}
        return o;
    }

    public void cacheRemoteUser(JSONObject remote,String password) {
        if(remote==null)return; String id=remote.optString("id","").toUpperCase(Locale.ITALY); if(id.isEmpty())return;
        JSONArray a=array("users"); JSONObject target=null;
        for(int i=0;i<a.length();i++){JSONObject u=a.optJSONObject(i);if(u!=null&&id.equalsIgnoreCase(u.optString("id"))){target=u;break;}}
        if(target==null){target=new JSONObject();a.put(target);}
        try{
            target.put("id",id);target.put("name",remote.optString("name",id));target.put("role",remote.optString("role","LETTURA"));
            target.put("enabled",remote.optBoolean("enabled",true));
            if(password!=null&&!password.isEmpty())target.put("passwordHash",hash(password));
            else if(!target.has("passwordHash"))target.put("passwordHash","");
            touch(target);
        }catch(Exception ignored){}
        saveArray("users",a);
    }

    public void mergeRemoteUsers(JSONArray remoteUsers) {
        if(remoteUsers==null)return; suppressChange=true;
        try{
            JSONArray local=array("users");
            for(int r=0;r<remoteUsers.length();r++){
                JSONObject ru=remoteUsers.optJSONObject(r); if(ru==null)continue; String id=ru.optString("id",""); if(id.isEmpty())continue;
                JSONObject lu=null;
                for(int i=0;i<local.length();i++){JSONObject x=local.optJSONObject(i);if(x!=null&&id.equalsIgnoreCase(x.optString("id"))){lu=x;break;}}
                if(lu==null){lu=new JSONObject();local.put(lu);}
                String oldHash=lu.optString("passwordHash","");
                try{lu.put("id",id);lu.put("name",ru.optString("name",id));lu.put("role",ru.optString("role","LETTURA"));lu.put("enabled",ru.optBoolean("enabled",true));lu.put("passwordHash",oldHash);touch(lu);}catch(Exception ignored){}
            }
            saveArray("users",local);
        }finally{suppressChange=false;}
    }

    public JSONObject findUser(String id) {
        JSONArray a=array("users"); for(int i=0;i<a.length();i++){JSONObject u=a.optJSONObject(i);if(u!=null&&u.optString("id").equalsIgnoreCase(id.trim()))return u;} return null;
    }
    public boolean userIdExists(String id){return findUser(id)!=null;}
    public boolean verifyPassword(JSONObject user,String password){return user!=null&&user.optBoolean("enabled",true)&&hash(password).equals(user.optString("passwordHash"));}
    public boolean needsPassword(JSONObject user){return user!=null&&user.optString("passwordHash","").isEmpty();}

    public void setPassword(String id,String password){JSONArray a=array("users");for(int i=0;i<a.length();i++){JSONObject u=a.optJSONObject(i);if(u!=null&&u.optString("id").equalsIgnoreCase(id)){try{u.put("passwordHash",hash(password));touch(u);}catch(Exception ignored){}break;}}saveArray("users",a);}
    public void toggleUserEnabled(String id){JSONArray a=array("users");for(int i=0;i<a.length();i++){JSONObject u=a.optJSONObject(i);if(u!=null&&u.optString("id").equalsIgnoreCase(id)){try{u.put("enabled",!u.optBoolean("enabled",true));touch(u);}catch(Exception ignored){}break;}}saveArray("users",a);}
    public void setUserEnabled(String id,boolean enabled){JSONArray a=array("users");for(int i=0;i<a.length();i++){JSONObject u=a.optJSONObject(i);if(u!=null&&u.optString("id").equalsIgnoreCase(id)){try{u.put("enabled",enabled);touch(u);}catch(Exception ignored){}break;}}saveArray("users",a);}

    public void setCurrentUser(String id){prefs.edit().putString("currentUser",id).apply();}
    public String currentUserId(){return prefs.getString("currentUser","");}
    public JSONObject currentUser(){return findUser(currentUserId());}
    public void logout(){prefs.edit().remove("currentUser").apply();}

    public JSONObject addMachine(String name,String area,String note){JSONArray a=array("machines");JSONObject o=machine(name,area,note);a.put(o);saveArray("machines",a);return o;}

    public JSONObject addFault(String machine,String title,String description,String priority,String createdBy){JSONArray a=array("faults");JSONObject o=new JSONObject();try{o.put("id",shortId());o.put("machine",machine);o.put("title",title);o.put("description",description);o.put("priority",priority);o.put("status","DA_FARE");o.put("createdBy",createdBy);o.put("assignedTo","");o.put("createdAt",now());o.put("photoUri","");touch(o);a.put(o);saveArray("faults",a);}catch(Exception ignored){}return o;}
    public JSONObject findFault(String id){JSONArray a=array("faults");for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null&&id.equals(o.optString("id")))return o;}return null;}
    public void updateFault(String id,String status,String assignedTo,String photoUri){JSONArray a=array("faults");for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null&&id.equals(o.optString("id"))){try{if(status!=null)o.put("status",status);if(assignedTo!=null)o.put("assignedTo",assignedTo);if(photoUri!=null)o.put("photoUri",photoUri);touch(o);}catch(Exception ignored){}break;}}saveArray("faults",a);}

    public JSONObject addIntervention(String faultId,String machine,String type,String note,String parts,String userId,String photoUri){JSONArray a=array("interventions");JSONObject o=new JSONObject();try{o.put("id",shortId());o.put("faultId",faultId==null?"":faultId);o.put("machine",machine);o.put("type",type);o.put("note",note);o.put("parts",parts);o.put("userId",userId);o.put("createdAt",now());o.put("photoUri",photoUri==null?"":photoUri);touch(o);a.put(o);saveArray("interventions",a);}catch(Exception ignored){}return o;}
    public JSONObject addLeave(String userId,String name,String type,String start,String end,String note){JSONArray a=array("leaves");JSONObject o=new JSONObject();try{o.put("id",shortId());o.put("userId",userId);o.put("name",name);o.put("type",type);o.put("start",start);o.put("end",end);o.put("note",note);o.put("status","INSERITA");o.put("createdAt",now());touch(o);a.put(o);saveArray("leaves",a);}catch(Exception ignored){}return o;}
    public void setLeaveStatus(String id,String status){JSONArray a=array("leaves");for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null&&id.equals(o.optString("id"))){try{o.put("status",status);touch(o);}catch(Exception ignored){}break;}}saveArray("leaves",a);}
    public JSONObject addCommunication(String type,String title,String when,String body,String target){JSONArray a=array("communications");JSONObject o=new JSONObject();try{o.put("id",shortId());o.put("type",type);o.put("title",title);o.put("when",when);o.put("body",body);o.put("target",target);o.put("createdAt",now());touch(o);a.put(o);saveArray("communications",a);}catch(Exception ignored){}return o;}
    public JSONObject addHandover(String userId,String text){JSONArray a=array("handovers");JSONObject o=new JSONObject();try{o.put("id",shortId());o.put("userId",userId);o.put("text",text);o.put("createdAt",now());touch(o);a.put(o);saveArray("handovers",a);}catch(Exception ignored){}return o;}
    public JSONObject addDocument(String title,String machine,String uri,String mime){JSONArray a=array("documents");JSONObject o=new JSONObject();try{o.put("id",shortId());o.put("title",title);o.put("machine",machine);o.put("uri",uri);o.put("mime",mime);o.put("createdAt",now());touch(o);a.put(o);saveArray("documents",a);}catch(Exception ignored){}return o;}

    public void setShift(String userId,String date,String code){JSONArray a=array("shifts");boolean found=false;for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null&&userId.equals(o.optString("userId"))&&date.equals(o.optString("date"))){try{o.put("code",code.trim().toUpperCase(Locale.ITALY));o.put("id",shiftKey(userId,date));touch(o);}catch(Exception ignored){}found=true;break;}}if(!found){JSONObject o=new JSONObject();try{o.put("id",shiftKey(userId,date));o.put("userId",userId);o.put("date",date);o.put("code",code.trim().toUpperCase(Locale.ITALY));touch(o);a.put(o);}catch(Exception ignored){}}saveArray("shifts",a);}
    public String getShift(String userId,String date){JSONArray a=array("shifts");for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null&&userId.equals(o.optString("userId"))&&date.equals(o.optString("date")))return o.optString("code","");}return"";}

    public void setTurnationImage(String weekStart,String uri){JSONObject map=turnationImages();try{map.put(weekStart,uri);}catch(Exception ignored){}JSONObject ts=jsonPref("turnationImageUpdated");try{ts.put(weekStart,isoNow());}catch(Exception ignored){}prefs.edit().putString("turnationImages",map.toString()).putString("turnationImageUpdated",ts.toString()).putString("turnationWeek",weekStart).putString("turnationImage",uri).apply();changed();}
    private JSONObject turnationImages(){try{String raw=prefs.getString("turnationImages","");if(raw!=null&&!raw.isEmpty())return new JSONObject(raw);}catch(Exception ignored){}JSONObject map=new JSONObject();String oldWeek=prefs.getString("turnationWeek","");String oldImage=prefs.getString("turnationImage","");if(!oldWeek.isEmpty()&&!oldImage.isEmpty())try{map.put(oldWeek,oldImage);}catch(Exception ignored){}return map;}
    public String turnationImage(String weekStart){return turnationImages().optString(weekStart,"");}
    public void setTurnationOcrText(String weekStart,String text){JSONObject map=jsonPref("turnationOcr");JSONObject ts=jsonPref("turnationOcrUpdated");try{map.put(weekStart,text==null?"":text);ts.put(weekStart,isoNow());}catch(Exception ignored){}prefs.edit().putString("turnationOcr",map.toString()).putString("turnationOcrUpdated",ts.toString()).apply();changed();}
    public String turnationOcrText(String weekStart){return jsonPref("turnationOcr").optString(weekStart,"");}
    public String turnationWeek(){return prefs.getString("turnationWeek","");}
    public String turnationImage(){return prefs.getString("turnationImage","");}

    private JSONObject jsonPref(String key){try{return new JSONObject(prefs.getString(key,"{}"));}catch(Exception e){return new JSONObject();}}

    public JSONObject exportSyncSnapshot(){
        JSONObject root=new JSONObject();
        try{
            for(String key:SYNC_COLLECTIONS){
                JSONArray src=array(key), out=new JSONArray();
                for(int i=0;i<src.length();i++){
                    JSONObject original=src.optJSONObject(i);if(original==null)continue;
                    JSONObject o=new JSONObject(original.toString());
                    // content:// URIs only exist on this phone. Do not leak unusable references to other clients.
                    if("documents".equals(key)&&o.optString("uri").startsWith("content://"))o.put("uri","");
                    if(("faults".equals(key)||"interventions".equals(key))&&o.optString("photoUri").startsWith("content://"))o.put("photoUri","");
                    out.put(o);
                }
                root.put(key,out);
            }
            JSONArray userProfiles=new JSONArray();JSONArray us=array("users");for(int i=0;i<us.length();i++){JSONObject u=us.optJSONObject(i);if(u==null)continue;JSONObject p=new JSONObject();p.put("id",u.optString("id"));p.put("name",u.optString("name"));p.put("role",u.optString("role","LETTURA"));p.put("enabled",u.optBoolean("enabled",true));p.put("updatedAt",u.optString("updatedAt",isoNow()));userProfiles.put(p);}root.put("users",userProfiles);
            root.put("turnationImages",mapToRecords(turnationImages(),jsonPref("turnationImageUpdated"),"uri"));
            root.put("turnationOcr",mapToRecords(jsonPref("turnationOcr"),jsonPref("turnationOcrUpdated"),"text"));
        }catch(Exception ignored){}
        return root;
    }

    private JSONArray mapToRecords(JSONObject values,JSONObject ts,String field)throws JSONException{
        JSONArray a=new JSONArray();Iterator<String> keys=values.keys();while(keys.hasNext()){String k=keys.next();String value=values.optString(k,"");if("uri".equals(field)&&value.startsWith("content://"))continue;JSONObject o=new JSONObject();o.put("id",k);o.put("weekStart",k);o.put(field,value);o.put("updatedAt",ts.optString(k,isoNow()));a.put(o);}return a;
    }

    public void mergeSyncSnapshot(JSONObject remote){
        if(remote==null)return;suppressChange=true;
        try{
            for(String key:SYNC_COLLECTIONS){JSONArray incoming=remote.optJSONArray(key);if(incoming!=null)saveArray(key,mergeArrays(array(key),incoming,"shifts".equals(key)));}
            mergeMapRecords(remote.optJSONArray("turnationImages"),"turnationImages","turnationImageUpdated","uri");
            mergeMapRecords(remote.optJSONArray("turnationOcr"),"turnationOcr","turnationOcrUpdated","text");
            JSONArray users=remote.optJSONArray("users");if(users!=null)mergeRemoteUsers(users);
        }finally{suppressChange=false;}
    }

    private JSONArray mergeArrays(JSONArray local,JSONArray incoming,boolean shift){
        try{
            for(int r=0;r<incoming.length();r++){
                JSONObject ro=incoming.optJSONObject(r);if(ro==null)continue;String key=shift?shiftKey(ro.optString("userId"),ro.optString("date")):ro.optString("id");if(key.isEmpty())continue;
                int found=-1;JSONObject lo=null;
                for(int i=0;i<local.length();i++){JSONObject x=local.optJSONObject(i);if(x==null)continue;String lk=shift?shiftKey(x.optString("userId"),x.optString("date")):x.optString("id");if(key.equalsIgnoreCase(lk)){found=i;lo=x;break;}}
                if(found<0)local.put(new JSONObject(ro.toString()));
                else if(compareStamp(ro.optString("updatedAt"),lo.optString("updatedAt"))>=0){
                    JSONObject replacement=new JSONObject(ro.toString());
                    if(replacement.optString("uri","").isEmpty()&&lo.optString("uri","").startsWith("content://"))replacement.put("uri",lo.optString("uri"));
                    if(replacement.optString("photoUri","").isEmpty()&&lo.optString("photoUri","").startsWith("content://"))replacement.put("photoUri",lo.optString("photoUri"));
                    local.put(found,replacement);
                }
            }
        }catch(Exception ignored){}
        return local;
    }

    private void mergeMapRecords(JSONArray a,String pref,String tsPref,String field){if(a==null)return;JSONObject vals=jsonPref(pref),ts=jsonPref(tsPref);for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;String id=o.optString("weekStart",o.optString("id"));String rt=o.optString("updatedAt","");if(id.isEmpty())continue;if(compareStamp(rt,ts.optString(id,""))>=0){try{vals.put(id,o.optString(field,""));ts.put(id,rt);}catch(Exception ignored){}}}prefs.edit().putString(pref,vals.toString()).putString(tsPref,ts.toString()).apply();}
    private int compareStamp(String a,String b){if(a==null)a="";if(b==null)b="";return a.compareTo(b);}

    public void clearWorkspaceForRemoteJoin(){
        suppressChange=true;
        try{
            for(String key:SYNC_COLLECTIONS)saveArray(key,new JSONArray());
            saveArray("users",new JSONArray());
            prefs.edit().remove("turnationImages").remove("turnationImageUpdated").remove("turnationOcr").remove("turnationOcrUpdated").remove("turnationWeek").remove("turnationImage").remove("currentUser").apply();
        }finally{suppressChange=false;}
    }

    public void setSetting(String key,String value){prefs.edit().putString("setting_"+key,value==null?"":value).apply();}
    public String getSetting(String key,String def){return prefs.getString("setting_"+key,def);}
    public void setBoolSetting(String key,boolean value){prefs.edit().putBoolean("setting_"+key,value).apply();}
    public boolean getBoolSetting(String key,boolean def){return prefs.getBoolean("setting_"+key,def);}
    public boolean isAdmin(JSONObject u){return u!=null&&"ADMIN".equals(u.optString("role"));}

    public void addDemoData(String adminId){if(prefs.getBoolean("demo_loaded",false))return;JSONObject f=addFault("2B60","[DEMO] Cesoia - taglio irregolare","Caso dimostrativo per testare Skilla Bot. Non è uno storico reale.","MEDIA",adminId);updateFault(f.optString("id"),"CHIUSO",adminId,"");addIntervention(f.optString("id"),"2B60","Meccanico","DEMO: tra le cause annotate come esempio: pressione durante il taglio, boccole cesoia, accumulatore. Verificare sempre procedure e dati reali.","",adminId,"");addCommunication("CORSO","[DEMO] Corso sicurezza","15/10/2026 08:00","Voce dimostrativa eliminabile.","Tutti");prefs.edit().putBoolean("demo_loaded",true).apply();}
    public void clearDemoFlag(){prefs.edit().putBoolean("demo_loaded",false).apply();}

    public static String now(){return new SimpleDateFormat("dd/MM/yyyy HH:mm",Locale.ITALY).format(new Date());}
    public static String isoNow(){SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US);f.setTimeZone(TimeZone.getTimeZone("UTC"));return f.format(new Date());}
    public static String shortId(){return UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ITALY);}
    public static String hash(String input){try{MessageDigest md=MessageDigest.getInstance("SHA-256");byte[] b=md.digest(input.getBytes(StandardCharsets.UTF_8));StringBuilder sb=new StringBuilder();for(byte x:b)sb.append(String.format(Locale.US,"%02x",x));return sb.toString();}catch(Exception e){return input;}}
}
