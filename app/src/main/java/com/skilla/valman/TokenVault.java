package com.skilla.valman;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class TokenVault {
    private static final String PREF="valman_secure";
    private static final String ALIAS="valman_token_key";
    private static final String FIELD="server_token";
    private final SharedPreferences prefs;

    public TokenVault(Context c){prefs=c.getSharedPreferences(PREF,Context.MODE_PRIVATE);}

    private SecretKey key() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(ks.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry)ks.getEntry(ALIAS,null)).getSecretKey();
        KeyGenerator kg=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return kg.generateKey();
    }

    public void save(String token){
        try{
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());
            byte[] iv=c.getIV();byte[] enc=c.doFinal((token==null?"":token).getBytes(StandardCharsets.UTF_8));
            String packed=Base64.encodeToString(iv,Base64.NO_WRAP)+"."+Base64.encodeToString(enc,Base64.NO_WRAP);
            prefs.edit().putString(FIELD,packed).apply();
        }catch(Exception e){prefs.edit().remove(FIELD).apply();}
    }

    public String load(){
        String packed=prefs.getString(FIELD,"");if(packed.isEmpty())return"";
        try{
            String[] p=packed.split("\\.",2);if(p.length!=2)return"";
            byte[] iv=Base64.decode(p[0],Base64.NO_WRAP), enc=Base64.decode(p[1],Base64.NO_WRAP);
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,iv));
            return new String(c.doFinal(enc),StandardCharsets.UTF_8);
        }catch(Exception e){return"";}
    }

    public void clear(){prefs.edit().remove(FIELD).apply();}
}
