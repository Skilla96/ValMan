package com.skilla.valman;

import android.os.Handler;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Supabase backend bridge used by ValMan.
 *
 * No secret/service-role key is embedded in the APK. The Android client uses
 * only the public project URL + publishable key and the signed-in user's JWT.
 */
public class RemoteApi {
    public interface Callback { void ok(JSONObject data); void error(int code,String message,JSONObject data); }

    private static final String[] RECORD_COLLECTIONS = new String[]{
            "machines","faults","interventions","leaves","communications","handovers","documents","shifts","turnationImages","turnationOcr"
    };

    public static String normalizeBase(String base){
        String s=base==null?"":base.trim();
        if(s.isEmpty())s=SupabaseConfig.PROJECT_URL;
        while(s.endsWith("/"))s=s.substring(0,s.length()-1);
        if(s.endsWith("/api/skilla"))s=s.substring(0,s.length()-11);
        else if(s.endsWith("/skilla"))s=s.substring(0,s.length()-7);
        return s;
    }

    /** Skilla AI remains separate from the database backend for now. */
    public static String skillaEndpoint(String base){return "";}

    public static void health(String base,Handler main,Callback cb){
        runAsync(main,cb,()->{
            Raw r=raw("GET",normalizeBase(base)+"/auth/v1/settings",null,"",null);
            if(!r.ok())return fail(r);
            JSONObject out=new JSONObject();out.put("ok",true);out.put("backend","SUPABASE");return out;
        });
    }

    /** Kept for source compatibility; Supabase is already initialized from its dashboard. */
    public static void bootstrap(String base,String setupCode,String id,String name,String password,Handler main,Callback cb){
        JSONObject d=new JSONObject();try{d.put("error","NOT_REQUIRED");}catch(Exception ignored){}
        main.post(()->cb.error(409,"Il progetto Supabase è già inizializzato.",d));
    }

    public static void login(String base,String loginId,String password,Handler main,Callback cb){
        runAsync(main,cb,()->{
            String b=normalizeBase(base);
            String rawLogin=loginId==null?"":loginId.trim();
            boolean explicitEmail=rawLogin.contains("@");

            // Preferred path: ID login through the public Edge Function. It can safely resolve
            // the Auth email server-side without exposing it to other clients.
            if(!explicitEmail){
                JSONObject proxyBody=new JSONObject();proxyBody.put("employeeId",rawLogin);proxyBody.put("password",password==null?"":password);
                Raw proxy=raw("POST",b+"/functions/v1/login-id",proxyBody.toString(),"",null);
                if(proxy.ok()){
                    JSONObject session=proxy.obj.optJSONObject("session"),profile=proxy.obj.optJSONObject("profile");
                    if(session==null||profile==null)throw new ApiException(500,"Risposta login ID non valida.",proxy.obj);
                    JSONObject user=new JSONObject();user.put("id",profile.optString("employeeId",rawLogin).toUpperCase(Locale.ITALY));user.put("name",profile.optString("name",rawLogin));user.put("role",profile.optString("role","LETTURA"));user.put("enabled",profile.optBoolean("enabled",true));
                    String resolved=proxy.obj.optString("loginEmail","");if(!resolved.isEmpty())user.put("authLogin",resolved);
                    JSONObject out=new JSONObject();out.put("ok",true);out.put("user",user);out.put("token",session.optString("access_token",""));out.put("refreshToken",session.optString("refresh_token",""));out.put("loginEmail",resolved);return out;
                }
                // 404 means the function has not been deployed yet. Fall back to the
                // deterministic internal email used by ValMan-created accounts.
                if(proxy.code!=404)return fail(proxy);
            }

            String email=explicitEmail?rawLogin.toLowerCase(Locale.ITALY):rawLogin.toLowerCase(Locale.ITALY)+"@valman.internal";
            JSONObject body=new JSONObject();body.put("email",email);body.put("password",password==null?"":password);
            Raw auth=raw("POST",b+"/auth/v1/token?grant_type=password",body.toString(),"",null);
            if(!auth.ok()){
                if(!explicitEmail){
                    JSONObject d=auth.obj==null?new JSONObject():auth.obj;
                    try{d.put("error","EMAIL_REQUIRED_FIRST_LOGIN");}catch(Exception ignored){}
                    throw new ApiException(auth.code,"Primo collegamento: usa l'email dell'account Supabase. Dopo il primo accesso ValMan ricorderà l'associazione con il tuo ID.",d);
                }
                return fail(auth);
            }

            String access=auth.obj.optString("access_token","");
            String refresh=auth.obj.optString("refresh_token","");
            JSONObject authUser=auth.obj.optJSONObject("user");
            String uid=authUser==null?"":authUser.optString("id","");
            if(access.isEmpty()||uid.isEmpty())throw new ApiException(500,"Sessione Supabase non valida.",new JSONObject());

            Raw profileRes=raw("GET",b+"/rest/v1/profiles?select=user_id,employee_id,name,role,enabled,updated_at&user_id=eq."+enc(uid)+"&limit=1",null,access,null);
            if(!profileRes.ok())return fail(profileRes);
            JSONObject p=profileRes.arr!=null&&profileRes.arr.length()>0?profileRes.arr.optJSONObject(0):null;
            if(p==null)throw new ApiException(403,"Questo account non è associato a un profilo ValMan.",new JSONObject());
            if(!p.optBoolean("enabled",true))throw new ApiException(403,"Account ValMan disabilitato.",new JSONObject());

            JSONObject user=profileToUser(p);user.put("authLogin",email);
            JSONObject out=new JSONObject();out.put("ok",true);out.put("user",user);out.put("token",access);out.put("refreshToken",refresh);out.put("loginEmail",email);
            return out;
        });
    }

    public static void refresh(String base,String refreshToken,Handler main,Callback cb){
        runAsync(main,cb,()->{
            if(refreshToken==null||refreshToken.trim().isEmpty())throw new ApiException(401,"Sessione scaduta.",new JSONObject());
            JSONObject body=new JSONObject();body.put("refresh_token",refreshToken);
            Raw r=raw("POST",normalizeBase(base)+"/auth/v1/token?grant_type=refresh_token",body.toString(),"",null);
            if(!r.ok())return fail(r);
            JSONObject out=new JSONObject();out.put("token",r.obj.optString("access_token",""));out.put("refreshToken",r.obj.optString("refresh_token",refreshToken));return out;
        });
    }

    public static void claim(String base,String id,String activationCode,String password,Handler main,Callback cb){
        runAsync(main,cb,()->{
            JSONObject p=new JSONObject();p.put("employeeId",id);p.put("activationCode",activationCode);p.put("password",password);
            Raw r=raw("POST",normalizeBase(base)+"/functions/v1/claim-account",p.toString(),"",null);
            if(!r.ok()){
                if(r.code==404)throw new ApiException(404,"Attivazione account non ancora disponibile sul backend: distribuire la funzione claim-account.",r.obj);
                return fail(r);
            }
            JSONObject session=r.obj.optJSONObject("session"),profile=r.obj.optJSONObject("profile");
            if(session==null||profile==null)throw new ApiException(500,"Risposta di attivazione non valida.",r.obj);
            JSONObject user=new JSONObject();
            user.put("id",profile.optString("employeeId",id).toUpperCase(Locale.ITALY));
            user.put("name",profile.optString("name",id));user.put("role",profile.optString("role","LETTURA"));user.put("enabled",profile.optBoolean("enabled",true));
            user.put("authLogin",user.optString("id").toLowerCase(Locale.ITALY)+"@valman.internal");
            JSONObject out=new JSONObject();out.put("ok",true);out.put("user",user);out.put("token",session.optString("access_token",""));out.put("refreshToken",session.optString("refresh_token",""));out.put("loginEmail",user.optString("authLogin"));
            return out;
        });
    }

    public static void sync(String base,String token,JSONObject snapshot,Handler main,Callback cb){
        runAsync(main,cb,()->syncNow(normalizeBase(base),token,snapshot));
    }

    private static JSONObject syncNow(String base,String token,JSONObject localSnapshot)throws Exception{
        if(token==null||token.isEmpty())throw new ApiException(401,"Accesso richiesto.",new JSONObject());

        Raw authUser=raw("GET",base+"/auth/v1/user",null,token,null);
        if(!authUser.ok())return fail(authUser);
        String uid=authUser.obj.optString("id","");
        if(uid.isEmpty())throw new ApiException(401,"Sessione non valida.",authUser.obj);

        Raw remoteRecords=raw("GET",base+"/rest/v1/records?select=collection,record_key,data,updated_at&order=collection.asc,record_key.asc",null,token,null);
        if(!remoteRecords.ok())return fail(remoteRecords);
        Raw profiles=raw("GET",base+"/rest/v1/profiles?select=user_id,employee_id,name,role,enabled,updated_at&order=name.asc",null,token,null);
        if(!profiles.ok())return fail(profiles);
        Raw staff=raw("GET",base+"/rest/v1/staff_directory?select=badge_code,roster_name,trade,team,active,updated_at&active=eq.true&order=team.asc,roster_name.asc",null,token,null);
        if(!staff.ok())return fail(staff);

        LinkedHashMap<String,JSONObject> merged=new LinkedHashMap<>();
        JSONArray rr=remoteRecords.arr==null?new JSONArray():remoteRecords.arr;
        for(int i=0;i<rr.length();i++){
            JSONObject row=rr.optJSONObject(i);if(row==null)continue;
            String collection=row.optString("collection"),key=row.optString("record_key");if(collection.isEmpty()||key.isEmpty())continue;
            JSONObject data=row.optJSONObject("data");if(data==null)data=new JSONObject();
            JSONObject normalized=rowFor(collection,key,data,row.optString("updated_at",data.optString("updatedAt",AppStore.isoNow())),uid);
            merged.put(collection+"|"+key,normalized);
        }

        JSONObject snap=localSnapshot==null?new JSONObject():localSnapshot;
        for(String collection:RECORD_COLLECTIONS){
            JSONArray a=snap.optJSONArray(collection);if(a==null)continue;
            for(int i=0;i<a.length();i++){
                JSONObject data=a.optJSONObject(i);if(data==null)continue;
                String key=data.optString("id","");
                if(key.isEmpty()&&"shifts".equals(collection))key=data.optString("userId")+"|"+data.optString("date");
                if(key.isEmpty())continue;
                String stamp=data.optString("updatedAt",AppStore.isoNow());
                JSONObject local=rowFor(collection,key,new JSONObject(data.toString()),stamp,uid);
                JSONObject old=merged.get(collection+"|"+key);
                if(old==null||stamp.compareTo(rowStamp(old))>=0)merged.put(collection+"|"+key,local);
            }
        }

        JSONArray upload=new JSONArray();for(JSONObject row:merged.values())upload.put(row);
        if(upload.length()>0){
            LinkedHashMap<String,String> h=new LinkedHashMap<>();h.put("Prefer","resolution=merge-duplicates,return=minimal");
            Raw up=raw("POST",base+"/rest/v1/records?on_conflict=collection,record_key",upload.toString(),token,h);
            if(!up.ok())return fail(up);
        }

        JSONObject mergedSnapshot=new JSONObject();for(String collection:RECORD_COLLECTIONS)mergedSnapshot.put(collection,new JSONArray());
        for(JSONObject row:merged.values()){
            String collection=row.optString("collection");JSONArray a=mergedSnapshot.optJSONArray(collection);if(a!=null)a.put(new JSONObject(row.optJSONObject("data").toString()));
        }

        JSONArray users=new JSONArray();JSONArray pa=profiles.arr==null?new JSONArray():profiles.arr;
        for(int i=0;i<pa.length();i++){JSONObject p=pa.optJSONObject(i);if(p!=null)users.put(profileToUser(p));}
        mergedSnapshot.put("users",users);
        mergedSnapshot.put("staffDirectory",staff.arr==null?new JSONArray():staff.arr);

        JSONObject out=new JSONObject();out.put("ok",true);out.put("snapshot",mergedSnapshot);return out;
    }

    public static void createUser(String base,String token,String id,String name,String role,Handler main,Callback cb){
        runAsync(main,cb,()->{
            JSONObject p=new JSONObject();p.put("p_employee_id",id);p.put("p_name",name);p.put("p_role",role);
            Raw r=raw("POST",normalizeBase(base)+"/rest/v1/rpc/admin_create_employee",p.toString(),token,null);
            if(!r.ok())return fail(r);
            JSONObject first=r.arr!=null&&r.arr.length()>0?r.arr.optJSONObject(0):null;
            if(first==null)throw new ApiException(500,"Risposta creazione ID non valida.",new JSONObject());
            String employeeId=first.optString("employee_id",id).toUpperCase(Locale.ITALY);
            JSONObject user=new JSONObject();user.put("id",employeeId);user.put("name",name);user.put("role",role);user.put("enabled",true);
            JSONObject out=new JSONObject();out.put("ok",true);out.put("user",user);out.put("activationCode",first.optString("activation_code",""));return out;
        });
    }

    public static void setUserEnabled(String base,String token,String id,boolean enabled,Handler main,Callback cb){
        runAsync(main,cb,()->{
            String b=normalizeBase(base),q=enc(id.toUpperCase(Locale.ITALY));JSONObject p=new JSONObject();p.put("enabled",enabled);
            LinkedHashMap<String,String> h=new LinkedHashMap<>();h.put("Prefer","return=minimal");
            Raw a=raw("PATCH",b+"/rest/v1/profiles?employee_id=eq."+q,p.toString(),token,h);if(!a.ok())return fail(a);
            Raw i=raw("PATCH",b+"/rest/v1/employee_invites?employee_id=eq."+q,p.toString(),token,h);if(!i.ok())return fail(i);
            JSONObject out=new JSONObject();out.put("ok",true);out.put("enabled",enabled);return out;
        });
    }

    private static JSONObject profileToUser(JSONObject p)throws Exception{
        JSONObject u=new JSONObject();u.put("id",p.optString("employee_id","").toUpperCase(Locale.ITALY));u.put("name",p.optString("name",u.optString("id")));u.put("role",p.optString("role","LETTURA"));u.put("enabled",p.optBoolean("enabled",true));u.put("updatedAt",p.optString("updated_at",AppStore.isoNow()));return u;
    }

    private static JSONObject rowFor(String collection,String key,JSONObject data,String stamp,String uid)throws Exception{
        if(!data.has("updatedAt"))data.put("updatedAt",stamp);
        JSONObject row=new JSONObject();row.put("collection",collection);row.put("record_key",key);row.put("data",data);row.put("updated_at",stamp);row.put("updated_by",uid);return row;
    }
    private static String rowStamp(JSONObject row){JSONObject d=row.optJSONObject("data");return d==null?row.optString("updated_at",""):d.optString("updatedAt",row.optString("updated_at",""));}

    private interface Work { JSONObject run() throws Exception; }
    private static void runAsync(final Handler main,final Callback cb,final Work work){
        new Thread(()->{
            try{JSONObject out=work.run();main.post(()->cb.ok(out));}
            catch(ApiException e){final int c=e.code;final String m=e.getMessage();final JSONObject d=e.data;main.post(()->cb.error(c,m,d));}
            catch(Exception e){final String m=e.getMessage()==null?"Backend Supabase non raggiungibile":e.getMessage();main.post(()->cb.error(-1,m,new JSONObject()));}
        },"ValManSupabase").start();
    }

    private static JSONObject fail(Raw r)throws ApiException{
        JSONObject d=r.obj==null?new JSONObject():r.obj;
        String msg=d.optString("msg",d.optString("message",d.optString("error_description",d.optString("error","HTTP "+r.code))));
        throw new ApiException(r.code,msg,d);
    }

    private static class ApiException extends Exception{
        final int code;final JSONObject data;ApiException(int c,String m,JSONObject d){super(m);code=c;data=d==null?new JSONObject():d;}
    }

    private static class Raw{
        int code;String raw="";JSONObject obj=new JSONObject();JSONArray arr=null;
        boolean ok(){return code>=200&&code<300;}
    }

    private static Raw raw(String method,String url,String body,String token,Map<String,String> extra)throws Exception{
        HttpURLConnection c=null;Raw out=new Raw();
        try{
            c=(HttpURLConnection)new URL(url).openConnection();c.setRequestMethod(method);c.setConnectTimeout(12000);c.setReadTimeout(30000);
            c.setRequestProperty("Accept","application/json");c.setRequestProperty("Content-Type","application/json; charset=utf-8");c.setRequestProperty("apikey",SupabaseConfig.PUBLISHABLE_KEY);
            if(token!=null&&!token.isEmpty())c.setRequestProperty("Authorization","Bearer "+token);
            if(extra!=null)for(Map.Entry<String,String> e:extra.entrySet())c.setRequestProperty(e.getKey(),e.getValue());
            if(body!=null&&!("GET".equals(method))){c.setDoOutput(true);byte[] b=body.getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(b.length);try(OutputStream os=c.getOutputStream()){os.write(b);}}
            out.code=c.getResponseCode();InputStream in=out.ok()?c.getInputStream():c.getErrorStream();out.raw=readAll(in);
            String t=out.raw==null?"":out.raw.trim();
            if(!t.isEmpty()){
                if(t.startsWith("["))try{out.arr=new JSONArray(t);}catch(Exception ignored){}
                else try{out.obj=new JSONObject(t);}catch(Exception ignored){}
            }
            return out;
        }finally{if(c!=null)c.disconnect();}
    }

    private static String enc(String s)throws Exception{return URLEncoder.encode(s==null?"":s,"UTF-8");}
    private static String readAll(InputStream in)throws Exception{if(in==null)return"";StringBuilder b=new StringBuilder();try(BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){String line;while((line=r.readLine())!=null)b.append(line);}return b.toString();}
}
