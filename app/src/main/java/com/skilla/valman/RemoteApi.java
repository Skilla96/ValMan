package com.skilla.valman;

import android.os.Handler;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class RemoteApi {
    public interface Callback { void ok(JSONObject data); void error(int code,String message,JSONObject data); }

    public static String normalizeBase(String base){
        if(base==null)return"";String s=base.trim();while(s.endsWith("/"))s=s.substring(0,s.length()-1);
        if(s.endsWith("/api/skilla"))s=s.substring(0,s.length()-11);
        else if(s.endsWith("/skilla"))s=s.substring(0,s.length()-7);
        return s;
    }
    public static String skillaEndpoint(String base){String b=normalizeBase(base);return b.isEmpty()?"":b+"/api/skilla";}

    public static void health(String base,Handler main,Callback cb){request("GET",normalizeBase(base)+"/health",null,"",main,cb);}
    public static void bootstrap(String base,String setupCode,String id,String name,String password,Handler main,Callback cb){
        JSONObject p=new JSONObject();try{p.put("setupCode",setupCode);p.put("id",id);p.put("name",name);p.put("password",password);}catch(Exception ignored){}
        request("POST",normalizeBase(base)+"/api/bootstrap",p,"",main,cb);
    }
    public static void login(String base,String id,String password,Handler main,Callback cb){
        JSONObject p=new JSONObject();try{p.put("id",id);p.put("password",password);}catch(Exception ignored){}
        request("POST",normalizeBase(base)+"/api/auth/login",p,"",main,cb);
    }
    public static void claim(String base,String id,String password,Handler main,Callback cb){
        JSONObject p=new JSONObject();try{p.put("id",id);p.put("password",password);}catch(Exception ignored){}
        request("POST",normalizeBase(base)+"/api/auth/claim",p,"",main,cb);
    }
    public static void sync(String base,String token,JSONObject snapshot,Handler main,Callback cb){
        JSONObject p=new JSONObject();try{p.put("snapshot",snapshot);}catch(Exception ignored){}
        request("POST",normalizeBase(base)+"/api/sync/exchange",p,token,main,cb);
    }
    public static void createUser(String base,String token,String id,String name,String role,Handler main,Callback cb){
        JSONObject p=new JSONObject();try{p.put("id",id);p.put("name",name);p.put("role",role);}catch(Exception ignored){}
        request("POST",normalizeBase(base)+"/api/users",p,token,main,cb);
    }
    public static void setUserEnabled(String base,String token,String id,boolean enabled,Handler main,Callback cb){
        JSONObject p=new JSONObject();try{p.put("enabled",enabled);}catch(Exception ignored){}
        request("PATCH",normalizeBase(base)+"/api/users/"+encodePath(id),p,token,main,cb);
    }

    private static String encodePath(String s){return s==null?"":s.replaceAll("[^A-Za-z0-9_.-]","");}

    private static void request(final String method,final String url,final JSONObject body,final String token,final Handler main,final Callback cb){
        new Thread(()->{
            HttpURLConnection c=null;int code=-1;JSONObject parsed=new JSONObject();
            try{
                c=(HttpURLConnection)new URL(url).openConnection();c.setRequestMethod(method);c.setConnectTimeout(12000);c.setReadTimeout(30000);
                c.setRequestProperty("Accept","application/json");c.setRequestProperty("Content-Type","application/json; charset=utf-8");
                if(token!=null&&!token.isEmpty())c.setRequestProperty("Authorization","Bearer "+token);
                if(body!=null&&!("GET".equals(method))){c.setDoOutput(true);byte[] b=body.toString().getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(b.length);try(OutputStream os=c.getOutputStream()){os.write(b);}}
                code=c.getResponseCode();InputStream in=code>=200&&code<300?c.getInputStream():c.getErrorStream();String raw=readAll(in);
                if(raw!=null&&!raw.trim().isEmpty())try{parsed=new JSONObject(raw);}catch(Exception ignored){}
                final int fc=code;final JSONObject fp=parsed;
                if(code>=200&&code<300)main.post(()->cb.ok(fp));
                else{String m=parsed.optString("message",parsed.optString("error","HTTP "+code));main.post(()->cb.error(fc,m,fp));}
            }catch(Exception e){final String m=e.getMessage()==null?"Server non raggiungibile":e.getMessage();main.post(()->cb.error(-1,m,new JSONObject()));}
            finally{if(c!=null)c.disconnect();}
        },"ValManRemote").start();
    }

    private static String readAll(InputStream in)throws Exception{if(in==null)return"";StringBuilder b=new StringBuilder();try(BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){String line;while((line=r.readLine())!=null)b.append(line);}return b.toString();}
}
