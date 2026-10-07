package com.skilla.valman;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.speech.RecognitionListener;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.text.InputType;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CalendarView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Switch;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.DayOfWeek;
import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.Set;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {
    private static final int C_BG = Color.rgb(5, 15, 24);
    private static final int C_PANEL = Color.rgb(12, 31, 46);
    private static final int C_PANEL2 = Color.rgb(17, 45, 65);
    private static final int C_CARD = Color.rgb(13, 36, 53);
    private static final int C_BORDER = Color.rgb(34, 77, 103);
    private static final int C_TEXT = Color.rgb(242, 247, 251);
    private static final int C_MUTED = Color.rgb(151, 178, 196);
    private static final int C_BLUE = Color.rgb(44, 126, 238);
    private static final int C_CYAN = Color.rgb(52, 190, 232);
    private static final int C_GREEN = Color.rgb(24, 185, 105);
    private static final int C_ORANGE = Color.rgb(244, 145, 35);
    private static final int C_RED = Color.rgb(229, 70, 75);

    private static final int REQ_SPEECH = 100;
    private static final int REQ_TURNATION_IMAGE = 101;
    private static final int REQ_DOCUMENT = 102;
    private static final int REQ_FAULT_PHOTO = 103;
    private static final int REQ_INTERVENTION_PHOTO = 104;

    private AppStore store;
    private TokenVault tokenVault;
    private TextToSpeech tts;
    private EditText botInput;
    private LinearLayout botMessages;
    private String activeFaultId = "";
    private String pendingInterventionPhoto = "";
    private LocalDate turnationWeek;

    // Skilla Bot conversational state.
    private final ArrayList<String> botWho = new ArrayList<>();
    private final ArrayList<String> botText = new ArrayList<>();
    private String botContextMachine = "";
    private String botAwaiting = "";
    private String botPendingFaultMachine = "";
    private String botPendingFaultTitle = "";
    private boolean voiceConversation = false;
    private boolean botOnlineBusy = false;
    private String selectedVoiceName = "Voce di sistema";
    private String lastManualSearch = "";
    private MediaPlayer neuralPlayer;

    // Foreground wake phrase: active only while ValMan is open.
    private SpeechRecognizer speechRecognizer;
    private final Handler speechHandler = new Handler(Looper.getMainLooper());
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler syncHandler = new Handler(Looper.getMainLooper());
    private boolean syncBusy = false;
    private boolean activityForeground = false;
    private boolean speechBusy = false;
    private boolean ttsSpeaking = false;
    private String speechMode = "WAKE"; // inline recognizer is used only for wake phrase
    private boolean wakeConsumed = false;

    // Navigation state for Android system Back. This app renders screens inside one Activity,
    // so we explicitly mirror the same back action used by the header button.
    private Runnable currentBackAction = null;
    private String currentScreenKey = "";
    private boolean botCloseDialogVisible = false;

    // OCR draft for the currently imported weekly rota.
    private List<TurnationOcr.Row> pendingOcrRows = new ArrayList<>();
    private String pendingOcrRaw = "";
    private String pendingOcrImageUri = "";
    private LocalDate pendingOcrWeek = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new AppStore(this);
        tokenVault = new TokenVault(this);
        String oldBackend=store.getSetting("server_base_url","");
        String oldAi=store.getSetting("ai_endpoint","");
        if(!oldBackend.isEmpty()&&!RemoteApi.normalizeBase(oldBackend).equals(SupabaseConfig.PROJECT_URL)){
            tokenVault.clear();
            if(!oldAi.isEmpty()&&oldAi.startsWith(RemoteApi.normalizeBase(oldBackend)))store.setSetting("ai_endpoint","");
        }
        store.setSetting("server_base_url",SupabaseConfig.PROJECT_URL);
        store.setChangeListener(this::scheduleAutoSync);
        tts = new TextToSpeech(this, this);
        getWindow().setStatusBarColor(C_BG);
        getWindow().setNavigationBarColor(C_BG);
        if (!store.hasAdmin()) showLogin();
        else if (store.currentUser() != null) showDashboard();
        else showLogin();
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            configureBestItalianVoice();
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) { ttsSpeaking = true; }
                @Override public void onDone(String utteranceId) {
                    ttsSpeaking = false;
                    runOnUiThread(() -> {
                        if (voiceConversation && store != null && store.currentUser() != null) {
                            speechHandler.postDelayed(MainActivity.this::startVoiceCommand, 650);
                        } else scheduleWake(550);
                    });
                }
                @Override public void onError(String utteranceId) {
                    ttsSpeaking = false;
                    runOnUiThread(() -> scheduleWake(700));
                }
            });
        }
    }

    private void configureBestItalianVoice() {
        if (tts == null) return;
        tts.setLanguage(Locale.ITALIAN);
        tts.setSpeechRate(0.94f);
        tts.setPitch(0.97f);
        if (Build.VERSION.SDK_INT < 21 || tts.getVoices() == null) return;
        Voice best = null;
        int bestScore = Integer.MIN_VALUE;
        boolean preferNaturalOnline = store == null || store.getBoolSetting("natural_voice", true);
        for (Voice v : tts.getVoices()) {
            if (v == null || v.getLocale() == null || !"it".equalsIgnoreCase(v.getLocale().getLanguage())) continue;
            int score = v.getQuality() * 10 - v.getLatency();
            if ("IT".equalsIgnoreCase(v.getLocale().getCountry())) score += 400;
            if (preferNaturalOnline && v.isNetworkConnectionRequired()) score += 250;
            if (!preferNaturalOnline && !v.isNetworkConnectionRequired()) score += 250;
            String name = v.getName() == null ? "" : v.getName().toLowerCase(Locale.ITALY);
            if (name.contains("natural") || name.contains("neural") || name.contains("premium")) score += 350;
            if (score > bestScore) { bestScore = score; best = v; }
        }
        if (best != null && tts.setVoice(best) == TextToSpeech.SUCCESS) selectedVoiceName = best.getName();
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityForeground = true;
        scheduleWake(650);
    }

    @Override
    protected void onPause() {
        activityForeground = false;
        stopSpeechRecognizer();
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if ("BOT".equals(currentScreenKey)) {
            confirmCloseBot();
            return;
        }
        if (currentBackAction != null) {
            Runnable back = currentBackAction;
            currentBackAction = null;
            back.run();
            return;
        }
        if (store != null && store.currentUser() != null && "DASHBOARD".equals(currentScreenKey)) {
            new AlertDialog.Builder(this)
                    .setTitle("ValMan")
                    .setMessage("Sei nella Home. Vuoi chiudere ValMan?")
                    .setPositiveButton("Chiudi", (d,w) -> finish())
                    .setNegativeButton("Annulla", null)
                    .show();
            return;
        }
        super.onBackPressed();
    }

    private void confirmCloseBot() {
        if (botCloseDialogVisible) return;
        botCloseDialogVisible = true;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Chiudere chat con Skilla Bot?")
                .setMessage("La conversazione e il contesto tecnico attuale verranno azzerati. I dati salvati in ValMan non vengono modificati.")
                .setPositiveButton("Chiudi chat", (d,w) -> {
                    botCloseDialogVisible = false;
                    resetBotSession();
                    showDashboard();
                    scheduleWake(650);
                })
                .setNegativeButton("Continua chat", (d,w) -> botCloseDialogVisible = false)
                .create();
        dialog.setOnCancelListener(d -> botCloseDialogVisible = false);
        dialog.show();
    }

    private void resetBotSession() {
        voiceConversation = false;
        botOnlineBusy = false;
        stopSpeechRecognizer();
        speechHandler.removeCallbacksAndMessages(null);
        if (tts != null) { try { tts.stop(); } catch (Exception ignored) {} }
        ttsSpeaking = false;
        if (neuralPlayer != null) {
            try { neuralPlayer.stop(); } catch (Exception ignored) {}
            try { neuralPlayer.release(); } catch (Exception ignored) {}
            neuralPlayer = null;
        }
        botWho.clear();
        botText.clear();
        botContextMachine = "";
        botAwaiting = "";
        botPendingFaultMachine = "";
        botPendingFaultTitle = "";
        lastManualSearch = "";
        botInput = null;
        botMessages = null;
    }

    @Override
    protected void onDestroy() {
        speechHandler.removeCallbacksAndMessages(null);
        mainHandler.removeCallbacksAndMessages(null);
        syncHandler.removeCallbacksAndMessages(null);
        if (speechRecognizer != null) { try { speechRecognizer.destroy(); } catch (Exception ignored) {} speechRecognizer = null; }
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (neuralPlayer != null) { try { neuralPlayer.release(); } catch (Exception ignored) {} neuralPlayer=null; }
        super.onDestroy();
    }

    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }

    private GradientDrawable bg(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        d.setStroke(dp(1), C_BORDER);
        return d;
    }

    private GradientDrawable grad(int start, int end, int radius) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{start, end});
        d.setCornerRadius(dp(radius));
        d.setStroke(dp(1), C_BORDER);
        return d;
    }

    private LinearLayout screen() {
        currentBackAction = null;
        currentScreenKey = "";
        ScrollView s = new ScrollView(this);
        s.setFillViewport(true);
        s.setBackgroundColor(C_BG);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(15), dp(12), dp(15), dp(30));
        s.addView(l, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(s);
        if (store != null && store.currentUser() != null) addGlobalSkillaBar(l);
        return l;
    }

    private void addGlobalSkillaBar(LinearLayout root) {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), dp(10), dp(14), dp(10));
        bar.setBackground(grad(Color.rgb(17, 55, 78), Color.rgb(12, 38, 57), 16));
        bar.setElevation(dp(3));
        TextView mic = text("●", 16, C_CYAN, true);
        mic.setGravity(Gravity.CENTER);
        boolean micReady=checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean aiReady=store!=null&&!store.getSetting("ai_endpoint","").trim().isEmpty();
        TextView label = text(micReady
                        ? "Hey Skilla  •  voce pronta  •  "+(aiReady?"AI online":"AI locale")
                        : "Attiva Hey Skilla",
                13, C_TEXT, true);
        TextView hint = text("Tocca", 11, C_MUTED, true);
        hint.setGravity(Gravity.RIGHT);
        bar.addView(mic, new LinearLayout.LayoutParams(dp(26), ViewGroup.LayoutParams.WRAP_CONTENT));
        bar.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(hint);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(12)); bar.setLayoutParams(lp);
        bar.setOnClickListener(v -> {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 55);
            } else {
                voiceConversation=true;
                showBot();
                startVoiceCommand();
            }
        });
        root.addView(bar);
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(sp); t.setTextColor(color); t.setPadding(0, dp(3), 0, dp(3));
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private TextView title(String value) { return text(value, 22, C_TEXT, true); }
    private TextView sub(String value) { return text(value, 14, C_MUTED, false); }

    private EditText input(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint); e.setHintTextColor(Color.rgb(111, 145, 166)); e.setTextColor(C_TEXT); e.setTextSize(16);
        e.setSingleLine(true); e.setPadding(dp(15), dp(11), dp(15), dp(11)); e.setBackground(bg(Color.rgb(10, 29, 43), 14));
        e.setElevation(dp(1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)); lp.setMargins(0, dp(6), 0, dp(7)); e.setLayoutParams(lp); return e;
    }

    private EditText multiInput(String hint) {
        EditText e=input(hint); e.setSingleLine(false); e.setGravity(Gravity.TOP); e.setMinLines(3); e.setMaxLines(6);
        e.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110))); return e;
    }

    private Button button(String label, int color) {
        Button b = new Button(this);
        b.setText(label); b.setTextColor(Color.WHITE); b.setTextSize(15); b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        int end = color == C_BLUE ? Color.rgb(25, 93, 190) : color == C_GREEN ? Color.rgb(13, 133, 75) : color == C_ORANGE ? Color.rgb(203, 103, 19) : color;
        b.setBackground(grad(color, end, 14));
        b.setElevation(dp(2));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)); lp.setMargins(0, dp(6), 0, dp(7)); b.setLayoutParams(lp); return b;
    }

    private LinearLayout card() {
        LinearLayout c=new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL); c.setPadding(dp(15), dp(14), dp(15), dp(14));
        c.setBackground(grad(C_CARD, Color.rgb(10, 29, 43), 16));
        c.setElevation(dp(2));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); lp.setMargins(0, dp(6), 0, dp(7)); c.setLayoutParams(lp); return c;
    }

    private void addHeader(LinearLayout root, String name, final Runnable back) {
        currentScreenKey = name == null ? "" : name;
        currentBackAction = back;
        LinearLayout h=new LinearLayout(this); h.setGravity(Gravity.CENTER_VERTICAL); h.setPadding(0, dp(2), 0, dp(8));
        if(back!=null){
            Button b=button("‹", C_PANEL2); b.setTextSize(30);
            LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(dp(50),dp(48)); bp.setMargins(0,0,dp(11),0); b.setLayoutParams(bp); b.setOnClickListener(v->back.run()); h.addView(b);
        }
        LinearLayout titles=new LinearLayout(this); titles.setOrientation(LinearLayout.VERTICAL);
        TextView kicker=text("VALMAN",10,C_CYAN,true); kicker.setLetterSpacing(0.16f);
        TextView t=text(name,24,C_TEXT,true);
        titles.addView(kicker); titles.addView(t);
        h.addView(titles,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1)); root.addView(h);
    }

    private String str(EditText e) { return e.getText().toString().trim(); }
    private void toast(String s) { Toast.makeText(this,s,Toast.LENGTH_SHORT).show(); }

    private void showAdminSetup() {
        LinearLayout r=screen(); currentScreenKey="ADMIN_SETUP"; r.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView logo=text("ValMan",38,C_TEXT,true); logo.setGravity(Gravity.CENTER); logo.setBackgroundResource(com.skilla.valman.R.drawable.bg_logo); logo.setPadding(dp(32),dp(24),dp(32),dp(24)); r.addView(logo);
        TextView a=sub("Manutenzione • configurazione iniziale"); a.setGravity(Gravity.CENTER); r.addView(a);

        LinearLayout existing=card(); existing.addView(text("Hai già un server ValMan?",17,C_CYAN,true));
        existing.addView(sub("Se un amministratore ha già configurato il server condiviso, inserisci il suo indirizzo e accedi con il tuo ID dipendente."));
        Button join=button("Collegati a ValMan condiviso",C_CYAN); existing.addView(join); join.setOnClickListener(v->showRemoteJoinSetup()); r.addView(existing);

        r.addView(label("Oppure crea il primo amministratore locale"));
        final EditText id=input("ID amministratore (es. PSCHILLACI)"); final EditText name=input("Nome visualizzato"); final EditText pass=input("Password amministratore"); pass.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        r.addView(id); r.addView(name); r.addView(pass);
        Button create=button("Crea amministratore locale",C_BLUE); r.addView(create);
        create.setOnClickListener(v->{
            if(str(id).length()<3||str(name).isEmpty()||str(pass).length()<6){toast("Compila i campi; password almeno 6 caratteri.");return;}
            store.addUser(str(id),str(name),"ADMIN",str(pass)); store.setCurrentUser(str(id).toUpperCase(Locale.ITALY)); showDashboard();
        });
        LinearLayout note=card(); note.addView(text("v0.5 • LOCAL-FIRST",13,C_ORANGE,true)); note.addView(sub("Puoi continuare a lavorare offline. Quando colleghi il server, guasti, interventi, turni, ferie, comunicazioni e consegne vengono sincronizzati tra i telefoni.")); r.addView(note);
    }

    private void showRemoteJoinSetup(){
        LinearLayout r=screen(); addHeader(r,"Collega server",this::showAdminSetup);
        EditText base=input("https://server-valman.example.com"); base.setText(remoteBase()); r.addView(label("Indirizzo HTTPS del server ValMan")); r.addView(base);
        LinearLayout info=card(); info.addView(text("Accesso colleghi",15,C_CYAN,true)); info.addView(sub("Dopo aver salvato l'indirizzo potrai entrare con l'ID creato dall'amministratore. Al primo accesso scegli tu la password.")); r.addView(info);
        Button save=button("Salva e vai al login",C_GREEN); r.addView(save);
        save.setOnClickListener(v->{String b=RemoteApi.normalizeBase(str(base));if(!b.startsWith("https://")){toast("Inserisci un indirizzo HTTPS valido.");return;}if(!store.hasAdmin())store.clearWorkspaceForRemoteJoin();store.setSetting("server_base_url",b);store.setSetting("ai_endpoint",RemoteApi.skillaEndpoint(b));tokenVault.clear();showLogin();});
    }

    private void showLogin() {
        LinearLayout r=screen(); currentScreenKey="LOGIN";
        TextView logo=text("ValMan",38,C_TEXT,true); logo.setGravity(Gravity.CENTER); logo.setBackgroundResource(com.skilla.valman.R.drawable.bg_logo); logo.setPadding(dp(32),dp(24),dp(32),dp(24)); r.addView(logo);
        TextView p=sub("Manutenzione • Supabase condiviso"); p.setGravity(Gravity.CENTER); r.addView(p);
        TextView cloud=sub("☁ Backend ValMan pronto");cloud.setGravity(Gravity.CENTER);r.addView(cloud);
        final EditText id=input("ID dipendente o email"); final EditText pass=input("Password"); pass.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD); r.addView(id);r.addView(pass);
        Button login=button("Accedi",C_BLUE); r.addView(login);
        login.setOnClickListener(v->{
            String raw=str(id), pw=str(pass);
            if(raw.isEmpty()){toast("Inserisci il tuo ID o la tua email.");return;}
            String uid=raw.contains("@")?raw:raw.toUpperCase(Locale.ITALY);
            performRemoteLogin(uid,pw);
        });
        Button first=button("Primo accesso / Attiva ID",C_GREEN);r.addView(first);
        first.setOnClickListener(v->{String uid=str(id).toUpperCase(Locale.ITALY);if(uid.isEmpty()||uid.contains("@")){toast("Inserisci prima l'ID dipendente assegnato dall'amministratore.");return;}showRemoteFirstPassword(uid);});
        Button status=button("☁ Stato Supabase",C_PANEL2);r.addView(status);status.setOnClickListener(v->showServerSettings());
        Button offline=button("Accesso offline con dati già salvati",C_PANEL2);r.addView(offline);
        offline.setOnClickListener(v->{String raw=str(id);JSONObject u=raw.contains("@")?store.findUserByAuthLogin(raw):store.findUser(raw);if(u!=null&&store.verifyPassword(u,str(pass))){store.setCurrentUser(u.optString("id"));showDashboard();}else toast("Per l'accesso offline devi aver già effettuato almeno un accesso su questo telefono.");});
        LinearLayout note=card();note.addView(text("Primo collegamento amministratore",13,C_CYAN,true));note.addView(sub("Se l'account Supabase è stato creato con una normale email, usa l'email la prima volta. ValMan memorizzerà poi l'associazione con il tuo ID."));r.addView(note);
    }

    private void showFirstPassword(final JSONObject u) {
        final EditText e=new EditText(this); e.setHint("Nuova password (minimo 6 caratteri)"); e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD); e.setPadding(dp(16),dp(12),dp(16),dp(12));
        new AlertDialog.Builder(this).setTitle("Primo accesso").setMessage("ID: "+u.optString("id")+"\nImposta la tua password personale.").setView(e)
                .setPositiveButton("Imposta",(d,w)->{String p=e.getText().toString(); if(p.length()<6){toast("Password troppo corta.");return;} store.setPassword(u.optString("id"),p); store.setCurrentUser(u.optString("id")); showDashboard();})
                .setNegativeButton("Annulla",null).show();
    }


    private String remoteBase(){ return SupabaseConfig.PROJECT_URL; }
    private boolean remoteReady(){ return !remoteBase().isEmpty() && tokenVault!=null && !tokenVault.load().isEmpty(); }

    private void performRemoteLogin(final String id,final String password){
        String login=id==null?"":id.trim();
        if(!login.contains("@")){JSONObject cached=store.findUser(login);if(cached!=null&&!cached.optString("authLogin","").isEmpty())login=cached.optString("authLogin");}
        final String authLogin=login;
        final String original=id==null?"":id.trim();
        RemoteApi.login(remoteBase(),authLogin,password,mainHandler,new RemoteApi.Callback(){
            @Override public void ok(JSONObject data){
                JSONObject u=data.optJSONObject("user"); String token=data.optString("token","");String refresh=data.optString("refreshToken","");
                if(u==null||token.isEmpty()){toast("Risposta Supabase non valida.");return;}
                try{if(u.optString("authLogin","").isEmpty())u.put("authLogin",data.optString("loginEmail",authLogin));}catch(Exception ignored){}
                tokenVault.saveSession(token,refresh); store.cacheRemoteUser(u,password); store.setCurrentUser(u.optString("id"));
                runRemoteSync(false); showDashboard();
            }
            @Override public void error(int code,String message,JSONObject data){
                JSONObject local=original.contains("@")?store.findUserByAuthLogin(original):store.findUser(original);
                if(local!=null&&store.verifyPassword(local,password)){toast("Supabase non raggiungibile: accesso offline.");store.setCurrentUser(local.optString("id"));showDashboard();return;}
                toast("Accesso non riuscito: "+message);
            }
        });
    }

    private void showRemoteFirstPassword(final String id){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(18),dp(6),dp(18),0);
        final EditText code=new EditText(this);code.setHint("Codice di attivazione");code.setPadding(dp(12),dp(12),dp(12),dp(12));
        final EditText e=new EditText(this);e.setHint("Nuova password (minimo 8 caratteri)");e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);e.setPadding(dp(12),dp(12),dp(12),dp(12));
        box.addView(code);box.addView(e);
        new AlertDialog.Builder(this).setTitle("Attiva account ValMan").setMessage("ID: "+id+"\nInserisci il codice ricevuto dall'amministratore e scegli la tua password personale.").setView(box)
                .setPositiveButton("Attiva",(d,w)->{String pw=e.getText().toString();String activation=code.getText().toString().trim();if(activation.length()<4){toast("Inserisci il codice di attivazione.");return;}if(pw.length()<8){toast("Password troppo corta: usa almeno 8 caratteri.");return;}RemoteApi.claim(remoteBase(),id,activation,pw,mainHandler,new RemoteApi.Callback(){
                    @Override public void ok(JSONObject data){JSONObject u=data.optJSONObject("user");String token=data.optString("token","");String refresh=data.optString("refreshToken","");if(u==null||token.isEmpty()){toast("Risposta Supabase non valida.");return;}tokenVault.saveSession(token,refresh);store.cacheRemoteUser(u,pw);store.setCurrentUser(u.optString("id"));runRemoteSync(false);showDashboard();}
                    @Override public void error(int code,String message,JSONObject data){toast("Non riesco ad attivare l'account: "+message);}
                });}).setNegativeButton("Annulla",null).show();
    }

    private void scheduleAutoSync(){
        if(!remoteReady()||store==null||store.currentUser()==null)return;
        syncHandler.removeCallbacksAndMessages(null);syncHandler.postDelayed(()->runRemoteSync(false),1400);
    }

    private void runRemoteSync(final boolean manual){runRemoteSync(manual,false);}

    private void runRemoteSync(final boolean manual,final boolean retried){
        if(syncBusy||!remoteReady())return;syncBusy=true;
        RemoteApi.sync(remoteBase(),tokenVault.load(),store.exportSyncSnapshot(),mainHandler,new RemoteApi.Callback(){
            @Override public void ok(JSONObject data){syncBusy=false;JSONObject snap=data.optJSONObject("snapshot");if(snap!=null)store.mergeSyncSnapshot(snap);store.setSetting("last_sync",AppStore.now());if(manual)toast("Sincronizzazione Supabase completata.");}
            @Override public void error(int code,String message,JSONObject data){
                syncBusy=false;
                if(code==401&&!retried&&!tokenVault.loadRefresh().isEmpty()){
                    RemoteApi.refresh(remoteBase(),tokenVault.loadRefresh(),mainHandler,new RemoteApi.Callback(){
                        @Override public void ok(JSONObject d){String t=d.optString("token","");String r=d.optString("refreshToken",tokenVault.loadRefresh());if(t.isEmpty()){tokenVault.clear();if(manual)toast("Sessione scaduta: accedi di nuovo.");return;}tokenVault.saveSession(t,r);runRemoteSync(manual,true);}
                        @Override public void error(int c,String m,JSONObject d){tokenVault.clear();if(manual)toast("Sessione scaduta: accedi di nuovo.");}
                    });
                    return;
                }
                if(code==401)tokenVault.clear();if(manual)toast("Sincronizzazione non riuscita: "+message);
            }
        });
    }

    private void showDashboard() {
        final JSONObject u=store.currentUser(); if(u==null){showLogin();return;}
        LinearLayout r=screen(); currentScreenKey="DASHBOARD"; currentBackAction=null;

        LinearLayout hero=card();
        hero.setPadding(dp(17),dp(15),dp(17),dp(15));
        LinearLayout top=new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout names=new LinearLayout(this); names.setOrientation(LinearLayout.VERTICAL);
        TextView small=text("MANUTENZIONE • AREA PERSONALE",10,C_CYAN,true); small.setLetterSpacing(0.08f);
        names.addView(small);
        names.addView(text("Ciao, "+u.optString("name",u.optString("id")),23,C_TEXT,true));
        names.addView(sub(roleLabel(u.optString("role"))+"  •  "+AppStore.now()));
        top.addView(names,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));
        Button out=button("Esci",C_PANEL2); out.setTextSize(13); out.setLayoutParams(new LinearLayout.LayoutParams(dp(76),dp(44))); out.setOnClickListener(v->{store.logout();if(tokenVault!=null)tokenVault.clear();showLogin();}); top.addView(out);
        hero.addView(top);
        if(!remoteBase().isEmpty()){String ls=store.getSetting("last_sync","");hero.addView(sub(remoteReady()?"☁ Supabase condiviso"+(ls.isEmpty()?"":" • ultimo sync "+ls):"☁ Supabase pronto • accesso offline"));}
        r.addView(hero);
        scheduleAutoSync();

        int open=0, waiting=0, closed=0; JSONArray f=store.array("faults");
        for(int i=0;i<f.length();i++){String st=f.optJSONObject(i).optString("status"); if("CHIUSO".equals(st))closed++; else {open++; if("ATTESA_RICAMBI".equals(st))waiting++;}}
        LinearLayout stats=new LinearLayout(this); stats.setWeightSum(3);
        stats.addView(stat("APERTI",String.valueOf(open),C_RED),new LinearLayout.LayoutParams(0,dp(92),1));
        stats.addView(stat("RICAMBI",String.valueOf(waiting),C_ORANGE),new LinearLayout.LayoutParams(0,dp(92),1));
        stats.addView(stat("CHIUSI",String.valueOf(closed),C_GREEN),new LinearLayout.LayoutParams(0,dp(92),1)); r.addView(stats);

        LocalDate today=LocalDate.now(); String shift=store.getShift(u.optString("id"),today.toString());
        LinearLayout todayCard=card();
        LinearLayout todayHead=new LinearLayout(this); todayHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView dot=text("●",13,shift.isEmpty()?C_ORANGE:C_GREEN,true); todayHead.addView(dot);
        TextView todayLabel=text("  OGGI",11,C_CYAN,true); todayLabel.setLetterSpacing(0.08f); todayHead.addView(todayLabel);
        todayCard.addView(todayHead);
        todayCard.addView(text(shift.isEmpty()?"Turno non ancora pubblicato":"Turno di oggi  •  "+shift,19,C_TEXT,true));
        todayCard.addView(sub(shift.isEmpty()?"Apri Turnazione oppure chiedi direttamente a Skilla Bot.":"Chiedi “Hey Skilla, che turno faccio domani?” per consultare la settimana."));
        r.addView(todayCard);

        TextView quick=text("STRUMENTI",11,C_MUTED,true); quick.setLetterSpacing(0.12f); quick.setPadding(dp(2),dp(10),0,dp(5)); r.addView(quick);
        addGridButtons(r,
                new String[]{"Guasti","Impianti","Interventi","Skilla Bot","Consegne","Ferie / PAR","Turnazione","Comunicazioni","Documenti", store.isAdmin(u)?"Amministrazione":"Profilo"},
                new String[]{"Segnala e segui","Storico macchine","Lavori eseguiti","Assistente AI","Passaggio turno","Calendario assenze","Settimanale","Corsi e avvisi","Schemi e manuali",store.isAdmin(u)?"Utenti e dati":"Account"},
                new String[]{"⚒","▦","✓","AI","⇄","◷","▤","!","▱",store.isAdmin(u)?"⚙":"●"},
                new int[]{C_RED,C_CYAN,C_GREEN,C_BLUE,C_CYAN,C_ORANGE,C_CYAN,C_ORANGE,C_BLUE,C_MUTED},
                new Runnable[]{this::showFaults,this::showMachines,this::showInterventions,this::showBot,this::showHandovers,this::showLeaves,this::showTurnation,this::showCommunications,this::showDocuments, store.isAdmin(u)?this::showAdmin:this::showProfile});
    }

    private LinearLayout stat(String label,String value,int color){
        LinearLayout c=new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setGravity(Gravity.CENTER);
        c.setPadding(dp(5),dp(9),dp(5),dp(8)); c.setBackground(grad(Color.rgb(13,35,51),Color.rgb(9,27,40),14)); c.setElevation(dp(2));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(92),1); lp.setMargins(dp(3),dp(6),dp(3),dp(7)); c.setLayoutParams(lp);
        TextView v=text(value,27,C_TEXT,true); v.setGravity(Gravity.CENTER);
        TextView l=text(label,10,C_MUTED,true); l.setGravity(Gravity.CENTER); l.setLetterSpacing(0.06f);
        TextView line=new TextView(this); line.setBackgroundColor(color); c.addView(line,new LinearLayout.LayoutParams(dp(30),dp(3))); c.addView(v); c.addView(l); return c;
    }

    private void addGridButtons(LinearLayout r,String[] labels,String[] subtitles,String[] icons,int[] accents,Runnable[] actions){
        for(int i=0;i<labels.length;i+=2){
            LinearLayout row=new LinearLayout(this);
            for(int j=0;j<2;j++){
                int idx=i+j; if(idx>=labels.length)break;
                final Runnable action=actions[idx];
                LinearLayout tile=new LinearLayout(this); tile.setOrientation(LinearLayout.VERTICAL); tile.setPadding(dp(13),dp(12),dp(13),dp(11));
                tile.setBackground(grad(Color.rgb(15,42,61),Color.rgb(10,30,45),16)); tile.setElevation(dp(2));
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(112),1); lp.setMargins(dp(3),dp(4),dp(3),dp(4)); tile.setLayoutParams(lp);
                TextView icon=text(icons[idx],16,accents[idx],true); icon.setGravity(Gravity.CENTER); icon.setBackground(bg(Color.argb(40,Color.red(accents[idx]),Color.green(accents[idx]),Color.blue(accents[idx])),10)); icon.setPadding(dp(8),dp(4),dp(8),dp(4));
                tile.addView(icon,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,dp(32)));
                tile.addView(text(labels[idx],16,C_TEXT,true));
                tile.addView(text(subtitles[idx],11,C_MUTED,false));
                tile.setOnClickListener(v->action.run()); row.addView(tile);
            }
            r.addView(row);
        }
    }

    private String roleLabel(String r){ if("ADMIN".equals(r))return"Amministratore";if("ELETTRICO".equals(r))return"Manutentore elettrico";if("MECCANICO".equals(r))return"Manutentore meccanico";return r; }

    private void showFaults(){ LinearLayout r=screen();addHeader(r,"Guasti",this::showDashboard);Button n=button("+ Nuovo guasto",C_BLUE);r.addView(n);n.setOnClickListener(v->showNewFault()); JSONArray a=store.array("faults"); if(a.length()==0)r.addView(sub("Nessun guasto registrato.")); for(int i=a.length()-1;i>=0;i--){JSONObject o=a.optJSONObject(i);LinearLayout c=card();c.addView(text(o.optString("machine")+" • "+o.optString("title"),17,C_TEXT,true));c.addView(sub("#"+o.optString("id")+" • "+priorityLabel(o.optString("priority"))+" • "+statusLabel(o.optString("status"))));c.addView(sub(o.optString("description"))); final String id=o.optString("id");c.setOnClickListener(v->showFaultDetail(id));r.addView(c);} }

    private String priorityLabel(String p){return "ALTA".equals(p)?"🔴 Alta":"MEDIA".equals(p)?"🟠 Media":"🔵 Bassa";}
    private String statusLabel(String s){if("IN_LAVORAZIONE".equals(s))return"In lavorazione";if("ATTESA_RICAMBI".equals(s))return"In attesa ricambi";if("CHIUSO".equals(s))return"Chiuso";return"Da fare";}

    private void showNewFault(){LinearLayout r=screen();addHeader(r,"Nuovo guasto",this::showFaults); final Spinner m=machineSpinner(); final Spinner p=simpleSpinner(Arrays.asList("ALTA","MEDIA","BASSA"));final EditText t=input("Titolo / sintomo");final EditText d=multiInput("Descrivi cosa succede...");r.addView(label("Macchina"));r.addView(m);r.addView(label("Priorità"));r.addView(p);r.addView(t);r.addView(d);Button s=button("Registra guasto",C_GREEN);r.addView(s);s.setOnClickListener(v->{if(str(t).isEmpty()){toast("Inserisci il problema.");return;}store.addFault(String.valueOf(m.getSelectedItem()),str(t),str(d),String.valueOf(p.getSelectedItem()),store.currentUserId());showFaults();});}

    private TextView label(String s){return text(s,13,C_MUTED,true);}

    private Spinner machineSpinner(){JSONArray a=store.array("machines");ArrayList<String> names=new ArrayList<>();for(int i=0;i<a.length();i++)names.add(a.optJSONObject(i).optString("name"));return simpleSpinner(names);}
    private Spinner simpleSpinner(List<String> items){Spinner s=new Spinner(this);ArrayAdapter<String> ad=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,items){@Override public View getView(int p,View c,ViewGroup par){TextView v=(TextView)super.getView(p,c,par);v.setTextColor(C_TEXT);v.setTextSize(16);v.setPadding(dp(12),dp(12),dp(12),dp(12));return v;}@Override public View getDropDownView(int p,View c,ViewGroup par){TextView v=(TextView)super.getDropDownView(p,c,par);v.setTextColor(Color.BLACK);return v;}};ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);s.setAdapter(ad);s.setBackground(bg(C_PANEL,10));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(52));lp.setMargins(0,dp(6),0,dp(8));s.setLayoutParams(lp);return s;}

    private void showFaultDetail(String id){final JSONObject o=store.findFault(id);if(o==null){showFaults();return;}activeFaultId=id;LinearLayout r=screen();addHeader(r,"Dettaglio guasto",this::showFaults);LinearLayout c=card();c.addView(text(o.optString("machine")+" - "+o.optString("title"),21,C_TEXT,true));c.addView(text(priorityLabel(o.optString("priority"))+" • "+statusLabel(o.optString("status")),14,C_ORANGE,true));c.addView(sub("#"+id+" • "+o.optString("createdAt")+" • Segnalato da "+o.optString("createdBy")));c.addView(text(o.optString("description"),16,C_TEXT,false));r.addView(c);
        String pu=o.optString("photoUri");if(!pu.isEmpty()){ImageView im=new ImageView(this);im.setAdjustViewBounds(true);im.setMaxHeight(dp(260));try{im.setImageURI(Uri.parse(pu));}catch(Exception ignored){}r.addView(im);}Button photo=button("📷 Aggiungi / cambia foto",C_PANEL2);r.addView(photo);photo.setOnClickListener(v->pickFile("image/*",REQ_FAULT_PHOTO));
        if(!"CHIUSO".equals(o.optString("status"))){Button take=button("Prendi in carico",C_GREEN);r.addView(take);take.setOnClickListener(v->{store.updateFault(id,"IN_LAVORAZIONE",store.currentUserId(),null);showFaultDetail(id);});Button wait=button("In attesa ricambi",C_ORANGE);r.addView(wait);wait.setOnClickListener(v->{store.updateFault(id,"ATTESA_RICAMBI",store.currentUserId(),null);showFaultDetail(id);});}
        Button add=button("+ Registra intervento",C_BLUE);r.addView(add);add.setOnClickListener(v->showNewIntervention(id,o.optString("machine")));
        r.addView(label("Storico interventi"));JSONArray ints=store.array("interventions");boolean any=false;for(int i=ints.length()-1;i>=0;i--){JSONObject in=ints.optJSONObject(i);if(id.equals(in.optString("faultId"))){any=true;LinearLayout ic=card();ic.addView(text(in.optString("type")+" • "+in.optString("createdAt"),15,C_TEXT,true));ic.addView(sub(in.optString("note")));if(!in.optString("parts").isEmpty())ic.addView(sub("Ricambi: "+in.optString("parts")));r.addView(ic);}}if(!any)r.addView(sub("Nessun intervento registrato."));}

    private void showNewIntervention(final String faultId,final String machine){pendingInterventionPhoto="";LinearLayout r=screen();addHeader(r,"Nuovo intervento",()->showFaultDetail(faultId));Spinner type=simpleSpinner(Arrays.asList("MECCANICO","ELETTRICO","MISTO"));EditText note=multiInput("Cosa hai trovato e cosa hai fatto...");EditText parts=input("Ricambi utilizzati (opzionale)");r.addView(label("Macchina: "+machine));r.addView(type);r.addView(note);r.addView(parts);Button ph=button("📷 Aggiungi foto",C_PANEL2);r.addView(ph);ph.setOnClickListener(v->pickFile("image/*",REQ_INTERVENTION_PHOTO));Button save=button("Concludi intervento",C_GREEN);r.addView(save);save.setOnClickListener(v->{if(str(note).isEmpty()){toast("Descrivi l'intervento.");return;}store.addIntervention(faultId,machine,String.valueOf(type.getSelectedItem()),str(note),str(parts),store.currentUserId(),pendingInterventionPhoto);new AlertDialog.Builder(this).setTitle("Stato guasto").setMessage("Vuoi chiudere il guasto?").setPositiveButton("Chiudi",(d,w)->{store.updateFault(faultId,"CHIUSO",store.currentUserId(),null);showFaultDetail(faultId);}).setNegativeButton("Lascia aperto",(d,w)->showFaultDetail(faultId)).show();});}

    private void showMachines(){LinearLayout r=screen();addHeader(r,"Impianti",this::showDashboard);JSONObject u=store.currentUser();if(store.isAdmin(u)){Button add=button("+ Aggiungi impianto",C_BLUE);r.addView(add);add.setOnClickListener(v->showAddMachine());}JSONArray a=store.array("machines");for(int i=0;i<a.length();i++){JSONObject m=a.optJSONObject(i);LinearLayout c=card();c.addView(text(m.optString("name"),18,C_TEXT,true));c.addView(sub(m.optString("area")+" • "+m.optString("note")));String name=m.optString("name");int count=0;JSONArray f=store.array("faults");for(int j=0;j<f.length();j++)if(name.equalsIgnoreCase(f.optJSONObject(j).optString("machine")))count++;c.addView(sub(count+" guasti/interventi collegati"));r.addView(c);}}

    private void showAddMachine(){LinearLayout r=screen();addHeader(r,"Nuovo impianto",this::showMachines);EditText n=input("Nome macchina / impianto");EditText a=input("Area / reparto");EditText no=input("Nota breve");r.addView(n);r.addView(a);r.addView(no);Button s=button("Salva",C_GREEN);r.addView(s);s.setOnClickListener(v->{if(str(n).isEmpty()){toast("Inserisci il nome.");return;}store.addMachine(str(n),str(a),str(no));showMachines();});}

    private void showInterventions(){LinearLayout r=screen();addHeader(r,"Interventi",this::showDashboard);JSONArray a=store.array("interventions");if(a.length()==0)r.addView(sub("Nessun intervento registrato."));for(int i=a.length()-1;i>=0;i--){JSONObject o=a.optJSONObject(i);LinearLayout c=card();c.addView(text(o.optString("machine")+" • "+o.optString("type"),17,C_TEXT,true));c.addView(sub(o.optString("createdAt")+" • "+o.optString("userId")));c.addView(text(o.optString("note"),14,C_TEXT,false));if(!o.optString("parts").isEmpty())c.addView(sub("Ricambi: "+o.optString("parts")));r.addView(c);}}

    private void showBot() {
        LinearLayout r=screen(); addHeader(r,"Skilla Bot",this::confirmCloseBot); currentScreenKey="BOT";

        LinearLayout hero=card();
        hero.setBackground(grad(Color.rgb(18,58,83),Color.rgb(9,32,49),18));
        LinearLayout heroTop=new LinearLayout(this); heroTop.setGravity(Gravity.CENTER_VERTICAL);
        TextView orb=text("AI",16,C_CYAN,true); orb.setGravity(Gravity.CENTER); orb.setBackground(bg(Color.rgb(17,72,102),30));
        heroTop.addView(orb,new LinearLayout.LayoutParams(dp(48),dp(48)));
        LinearLayout heroCopy=new LinearLayout(this); heroCopy.setOrientation(LinearLayout.VERTICAL); heroCopy.setPadding(dp(12),0,0,0);
        heroCopy.addView(text("Skilla Bot • assistente tecnico",19,C_TEXT,true));
        heroCopy.addView(sub("Meccanica • elettrica • automazione • dati ValMan"));
        heroTop.addView(heroCopy,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));
        hero.addView(heroTop);
        hero.addView(text("Puoi parlare in modo naturale: se manca un dato ti faccio domande. Le richieste fuori dalla manutenzione restano fuori dal perimetro.",13,C_MUTED,false));
        r.addView(hero);

        LinearLayout voiceBar=card();
        TextView voiceTitle=text(voiceConversation?"🎙 Conversazione vocale attiva":"🎙 Modalità voce",16,C_TEXT,true);
        TextView voiceSub=sub(voiceConversation?"Dopo ogni risposta torno ad ascoltare. Tocca per fermare.":"Tocca per parlare: la risposta viene letta con la migliore voce italiana disponibile.");
        voiceBar.addView(voiceTitle); voiceBar.addView(voiceSub);
        voiceBar.setOnClickListener(v->{
            voiceConversation=!voiceConversation;
            if(voiceConversation) startVoiceCommand(); else { stopSpeechRecognizer(); scheduleWake(600); showBot(); }
        });
        r.addView(voiceBar);

        LinearLayout quick=card();
        quick.addView(text("Azioni rapide",13,C_CYAN,true));
        LinearLayout q1=new LinearLayout(this);
        Button diag=button("Diagnosi",C_PANEL2); Button explain=button("Spiegami",C_PANEL2);
        q1.addView(diag,new LinearLayout.LayoutParams(0,dp(48),1)); q1.addView(explain,new LinearLayout.LayoutParams(0,dp(48),1));
        quick.addView(q1);
        LinearLayout q2=new LinearLayout(this);
        Button manual=button("Manuale",C_PANEL2); Button shift=button("Il mio turno",C_PANEL2);
        q2.addView(manual,new LinearLayout.LayoutParams(0,dp(48),1)); q2.addView(shift,new LinearLayout.LayoutParams(0,dp(48),1));
        quick.addView(q2);
        diag.setOnClickListener(v->{ if(botInput!=null){botInput.setText("Ho un problema su ");botInput.requestFocus();} });
        explain.setOnClickListener(v->{ if(botInput!=null){botInput.setText("Spiegami la differenza tra ");botInput.requestFocus();} });
        manual.setOnClickListener(v->{ if(botInput!=null){botInput.setText("Cercami il manuale ufficiale di ");botInput.requestFocus();} });
        shift.setOnClickListener(v->submitBotQuery("Che turno faccio oggi?"));
        r.addView(quick);

        if(!botContextMachine.isEmpty()){
            LinearLayout ctx=card(); ctx.addView(text("Contesto attivo: "+botContextMachine,13,C_ORANGE,true));
            ctx.addView(sub("Le prossime domande brevi verranno collegate a questa macchina."));
            Button reset=button("Azzera contesto",C_PANEL2); ctx.addView(reset);
            reset.setOnClickListener(v->{botContextMachine="";botAwaiting="";showBot();}); r.addView(ctx);
        }

        botMessages=new LinearLayout(this); botMessages.setOrientation(LinearLayout.VERTICAL); r.addView(botMessages);
        if (botText.isEmpty()) addConversation("Skilla Bot","Dimmi pure. Posso aiutarti con guasti, turni, schemi, manuali e domande tecniche di manutenzione.",false);
        renderBotConversation();

        LinearLayout compose=new LinearLayout(this);
        botInput=input("Es. la bisella della SAS 2 non rientra");
        compose.addView(botInput,new LinearLayout.LayoutParams(0,dp(52),1));
        Button mic=button("🎙",C_ORANGE); mic.setLayoutParams(new LinearLayout.LayoutParams(dp(64),dp(52))); compose.addView(mic); r.addView(compose);
        Button send=button("Invia",C_BLUE); r.addView(send);
        send.setOnClickListener(v->{ voiceConversation=false; sendBot(); });
        mic.setOnClickListener(v->{ voiceConversation=true; startVoiceCommand(); });

        LinearLayout scope=card();
        scope.addView(text("Perimetro Skilla",13,C_CYAN,true));
        scope.addView(sub("Tecnica industriale e dati ValMan sì. Ricette, sport, gossip e richieste estranee no. Saluti e conversazione cordiale sono sempre ammessi."));
        r.addView(scope);
    }

    private void renderBotConversation() {
        if (botMessages == null) return;
        botMessages.removeAllViews();
        for (int i=0;i<botText.size();i++) addBotMessage(botWho.get(i),botText.get(i),"Tu".equals(botWho.get(i)));
    }

    private void addConversation(String who,String msg,boolean user) {
        botWho.add(who); botText.add(msg);
        while(botText.size()>30){ botText.remove(0); botWho.remove(0); }
        if (botMessages != null) addBotMessage(who,msg,user);
    }

    private void addBotMessage(String who,String msg,boolean user){
        if(botMessages==null)return;
        LinearLayout c=card(); c.setBackground(bg(user?Color.rgb(20,82,139):C_PANEL,14));
        c.addView(text(who,12,user?Color.rgb(180,220,255):C_ORANGE,true));
        TextView body=text(msg,15,C_TEXT,false);
        Linkify.addLinks(body,Linkify.WEB_URLS);
        body.setMovementMethod(LinkMovementMethod.getInstance());
        body.setLinkTextColor(C_CYAN);
        c.addView(body); botMessages.addView(c);
    }

    private void addManualSearchButton(String query){
        if(botMessages==null || query==null || query.trim().isEmpty())return;
        Button b=button("🔎 Cerca manuale del costruttore sul Web",C_PANEL2);
        b.setOnClickListener(v->openOfficialManualSearch(query));
        botMessages.addView(b);
    }

    private void openOfficialManualSearch(String query){
        try{
            String q=(query+" manuale ufficiale pdf manufacturer").trim();
            Intent i=new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.google.com/search?q="+Uri.encode(q)));
            startActivity(i);
        }catch(Exception e){toast("Non riesco ad aprire la ricerca Web.");}
    }

    private void sendBot(){ if(botInput==null)return; String q=str(botInput); if(q.isEmpty())return; botInput.setText(""); submitBotQuery(q); }

    private static class BotResult {
        String answer="";
        boolean online=false;
        boolean manualSearch=false;
        String manualQuery="";
        BotResult answer(String a){answer=a;return this;}
        BotResult online(){online=true;return this;}
        BotResult manual(String q){manualSearch=true;manualQuery=q;return this;}
    }

    private void submitBotQuery(String q) {
        if(q==null||q.trim().isEmpty()||botOnlineBusy)return;
        String cleaned=normalizeTechnicalTranscript(q);
        addConversation("Tu",cleaned,true);
        BotResult result=answerBot(cleaned);
        String endpoint=store.getSetting("ai_endpoint","").trim();
        if(result.online && !endpoint.isEmpty()){
            botOnlineBusy=true;
            addConversation("Skilla Bot","Sto verificando la richiesta tecnica e le fonti disponibili…",false);
            JSONObject payload=buildAiPayload(cleaned);
            AiGateway.ask(endpoint,payload,tokenVault==null?"":tokenVault.load(),mainHandler,new AiGateway.Callback(){
                @Override public void onSuccess(String answer,String source){
                    botOnlineBusy=false;
                    String finalAnswer=answer;
                    if(source!=null&&!source.trim().isEmpty()&&!answer.toLowerCase(Locale.ITALY).contains("fonte")) finalAnswer += "\nFonte: "+source;
                    addConversation("Skilla Bot",finalAnswer,false);
                    speak(finalAnswer);
                }
                @Override public void onError(String message){
                    botOnlineBusy=false;
                    String fallback=result.answer.isEmpty()?"La parte AI online non è raggiungibile in questo momento. Posso comunque usare storico, turni, documenti locali e le funzioni offline.":result.answer;
                    addConversation("Skilla Bot",fallback,false);
                    if(result.manualSearch) addManualSearchButton(result.manualQuery);
                    speak(fallback);
                }
            });
            return;
        }
        String ans=result.answer;
        if(ans.isEmpty()) ans="Posso aiutarti se restiamo nel contesto della manutenzione industriale. Dimmi macchina, componente o domanda tecnica.";
        addConversation("Skilla Bot",ans,false);
        if(result.manualSearch) addManualSearchButton(result.manualQuery);
        speak(ans);
    }

    private BotResult answerBot(String q) {
        String x=normalizeSpeechText(q);
        JSONObject u=store.currentUser();
        BotResult r=new BotResult();

        if(TechnicalKnowledge.isSocial(x)) return r.answer(TechnicalKnowledge.socialReply(x));
        if(TechnicalKnowledge.isClearlyOffTopic(x)) return r.answer("Su quello passo: io sono dedicato alla manutenzione industriale. Se hai un guasto, una domanda tecnica, un manuale o qualcosa su turni e organizzazione, dimmi pure.");

        if(x.contains("turno")||x.contains("lavoro lun")||x.contains("lavoro mar")||x.contains("lavoro mer")||x.contains("lavoro gio")||x.contains("lavoro ven")||x.contains("lavoro sab")||x.contains("lavoro dom")){
            LocalDate d=resolveDay(x); String code=store.getShift(u.optString("id"),d.toString());
            return r.answer(code.isEmpty()?"Non trovo ancora un turno confermato per "+prettyDate(d)+". Se hai appena caricato il settimanale, completa prima la verifica OCR.":"Per "+prettyDate(d)+" risulta: "+spokenShift(code)+". Fonte: turnazione ValMan confermata.");
        }
        if(x.contains("ferie")||x.contains(" par ")||x.startsWith("par ")||x.contains("assente")){
            JSONArray a=store.array("leaves"); if(a.length()==0)return r.answer("Non risultano ferie o PAR inseriti nel calendario ValMan.");
            StringBuilder b=new StringBuilder("Assenze registrate: "); int shown=0;
            for(int i=0;i<a.length()&&shown<4;i++){JSONObject o=a.optJSONObject(i);if("RIFIUTATA".equals(o.optString("status")))continue;if(shown++>0)b.append("; ");b.append(o.optString("name")).append(" ").append(o.optString("type")).append(" ").append(o.optString("start")).append("→").append(o.optString("end"));}
            return r.answer(b.toString());
        }
        if(x.contains("corso")||x.contains("visita")||x.contains("comunicaz")){
            JSONArray a=store.array("communications"); if(a.length()==0)return r.answer("Non ci sono comunicazioni registrate.");
            StringBuilder b=new StringBuilder("Comunicazioni: "); for(int i=a.length()-1,shown=0;i>=0&&shown<3;i--,shown++){JSONObject o=a.optJSONObject(i);if(shown>0)b.append("; ");b.append(o.optString("title")).append(" - ").append(o.optString("when"));} return r.answer(b.toString());
        }
        if(x.contains("guasti aperti")||x.contains("guasto aperto")){
            JSONArray a=store.array("faults"); StringBuilder b=new StringBuilder(); int c=0;
            for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(!"CHIUSO".equals(o.optString("status"))){if(c++>0)b.append("; ");b.append(o.optString("machine")).append(": ").append(o.optString("title"));}}
            return r.answer(c==0?"Non risultano guasti aperti.":"Guasti aperti: "+b);
        }

        if(!botPendingFaultMachine.isEmpty() && (x.equals("si")||x.equals("sì")||x.contains("confermo")||x.contains("conferma"))){
            JSONObject f=store.addFault(botPendingFaultMachine,botPendingFaultTitle,botPendingFaultTitle,"MEDIA",store.currentUserId());
            String msg="Guasto creato su "+botPendingFaultMachine+" con ID #"+f.optString("id")+". Priorità impostata a media: puoi modificarla dalla scheda Guasti.";
            botPendingFaultMachine=""; botPendingFaultTitle="";
            return r.answer(msg);
        }
        if(!botPendingFaultMachine.isEmpty() && (x.contains("annulla")||x.equals("no"))){
            botPendingFaultMachine=""; botPendingFaultTitle="";
            return r.answer("Va bene, non ho creato nessun guasto.");
        }

        if((x.contains("apri")||x.contains("crea")||x.contains("segnala")) && x.contains("guasto")){
            MachineMatch actionMachine=findMachineMatch(x);
            if(actionMachine.machine.isEmpty()) return r.answer("Su quale macchina devo preparare il guasto?");
            String title=extractFaultDescription(x,actionMachine.machine);
            if(title.isEmpty()) { botContextMachine=actionMachine.machine; botAwaiting="FAULT_TITLE"; return r.answer("Ho capito "+actionMachine.machine+". Che problema devo inserire nel guasto?"); }
            botPendingFaultMachine=actionMachine.machine; botPendingFaultTitle=title;
            return r.answer("Preparo il guasto: "+actionMachine.machine+" • "+title+". Confermi la registrazione?");
        }
        if("FAULT_TITLE".equals(botAwaiting)&&!botContextMachine.isEmpty()){
            botAwaiting=""; botPendingFaultMachine=botContextMachine; botPendingFaultTitle=q.trim();
            return r.answer("Preparo il guasto: "+botPendingFaultMachine+" • "+botPendingFaultTitle+". Confermi la registrazione?");
        }

        if(x.contains("manuale")||x.contains("datasheet")||x.contains("schema")){
            BotResult docs=answerDocumentQuery(q,x);
            if(!docs.answer.isEmpty() || docs.online || docs.manualSearch) return docs;
        }

        MachineMatch mm=findMachineMatch(x);
        String machine=mm.machine;
        if (machine.isEmpty() && !botContextMachine.isEmpty() && looksLikeFollowUp(x)) machine=botContextMachine;
        if (mm.ambiguous.size()>1) {
            botAwaiting="MACHINE";
            return r.answer("Ho trovato più macchine compatibili: "+join(mm.ambiguous)+". Quale intendi?");
        }
        if (!machine.isEmpty()) {
            botContextMachine=machine;
            if (!hasSymptom(x,machine)) {
                botAwaiting="SYMPTOM";
                return r.answer("Ho capito: "+machine+". Che tipo di problema hai? Dimmi cosa fa o non fa la macchina, anche a parole tue.");
            }
            botAwaiting="DETAIL";
            String local=answerMachineHistory(machine,x);
            if(local.contains("Non trovo ancora un caso abbastanza simile") && !store.getSetting("ai_endpoint","").trim().isEmpty()) return r.answer(local).online();
            return r.answer(local);
        }

        if ("SYMPTOM".equals(botAwaiting) && !botContextMachine.isEmpty()) {
            botAwaiting="DETAIL";
            String local=answerMachineHistory(botContextMachine,x);
            if(local.contains("Non trovo ancora un caso abbastanza simile") && !store.getSetting("ai_endpoint","").trim().isEmpty()) return r.answer(local).online();
            return r.answer(local);
        }

        String offline=TechnicalKnowledge.offlineAnswer(x);
        if(!offline.isEmpty()){
            boolean needsWeb=(x.contains("radiocomando")||x.contains("manuale")||x.contains("datasheet"));
            if(needsWeb && !store.getSetting("ai_endpoint","").trim().isEmpty()) return r.answer(offline).online();
            return r.answer(offline);
        }

        if(TechnicalKnowledge.isTechnical(x)){
            String fallback="La domanda è nel mio ambito tecnico, ma per una risposta specifica mi servono più dati: marca/modello, componente interessato, sintomo e cosa hai già verificato.";
            if(!store.getSetting("ai_endpoint","").trim().isEmpty()) return r.answer(fallback).online();
            return r.answer(fallback+" La modalità AI online non è ancora configurata, quindi in questa build non invento una risposta.");
        }

        return r.answer("Posso parlare normalmente con te, ma per le risposte operative resto nel contesto di meccanica, elettrica, elettronica, automazione e manutenzione. Cosa devi fare?");
    }

    private BotResult answerDocumentQuery(String original,String x){
        BotResult r=new BotResult();
        JSONArray docs=store.array("documents");
        StringBuilder b=new StringBuilder(); int found=0;
        for(int i=docs.length()-1;i>=0&&found<4;i--){
            JSONObject d=docs.optJSONObject(i); if(d==null)continue;
            String hay=normalizeSpeechText(d.optString("title")+" "+d.optString("machine"));
            int score=keywordOverlap(hay,x);
            if(score>0){ if(found++==0)b.append("Ho trovato nei documenti ValMan: "); else b.append("; "); b.append(d.optString("title")).append(" [").append(d.optString("machine")).append("]"); }
        }
        if(found>0) return r.answer(b.append(". Apri Documenti per visualizzare il file. Fonte: archivio ValMan.").toString());
        String fallback="Non trovo ancora un documento interno che corrisponda. Per evitare procedure sbagliate, cercherei il manuale ufficiale del costruttore usando marca e modello esatti.";
        if(!store.getSetting("ai_endpoint","").trim().isEmpty()) return r.answer(fallback).online();
        return r.answer(fallback).manual(original);
    }

    private int keywordOverlap(String hay,String query){
        int score=0;
        for(String w:query.split("\\s+")){
            if(w.length()<3||isSearchStopWord(w)||"manuale".equals(w)||"schema".equals(w)||"datasheet".equals(w))continue;
            if(hay.contains(w))score++;
        }
        return score;
    }

    private String extractFaultDescription(String x,String machine){
        String y=x.replace(normalizeSpeechText(machine)," ").replace("2b60"," ").replace("sas2"," ")
                .replace("apri"," ").replace("crea"," ").replace("segnala"," ").replace("guasto"," ")
                .replaceAll("\\s+"," ").trim();
        return y;
    }

    private JSONObject buildAiPayload(String query){
        JSONObject p=new JSONObject();
        try{
            JSONObject u=store.currentUser();
            p.put("query",query);
            p.put("normalized",normalizeSpeechText(query));
            p.put("context_machine",botContextMachine);
            p.put("user_role",u==null?"":u.optString("role"));
            p.put("user_name",u==null?"":u.optString("name"));
            JSONArray history=new JSONArray();
            int start=Math.max(0,botText.size()-10);
            for(int i=start;i<botText.size();i++){
                JSONObject m=new JSONObject(); m.put("role","Tu".equals(botWho.get(i))?"user":"assistant"); m.put("text",botText.get(i)); history.put(m);
            }
            p.put("history",history);
            p.put("local_context",buildLocalAiContext(query));
            p.put("policy","Solo manutenzione industriale: meccanica, elettrica/elettronica, automazione, idraulica/pneumatica, officina, manuali, sicurezza e dati ValMan. Fuori tema: rifiuta cordialmente. Non comandare PLC o macchine. Per procedure specifiche usa fonti ufficiali e segnala incertezza.");
        }catch(Exception ignored){}
        return p;
    }

    private JSONObject buildLocalAiContext(String query){
        JSONObject out=new JSONObject();
        try{
            String q=normalizeSpeechText(query);
            JSONArray ih=new JSONArray(); JSONArray ints=store.array("interventions");
            for(int i=ints.length()-1;i>=0&&ih.length()<8;i--){JSONObject o=ints.optJSONObject(i);String h=normalizeSpeechText(o.optString("machine")+" "+o.optString("note")+" "+o.optString("parts"));if(keywordOverlap(h,q)>0||(!botContextMachine.isEmpty()&&botContextMachine.equalsIgnoreCase(o.optString("machine"))))ih.put(o);}
            JSONArray fh=new JSONArray(); JSONArray fs=store.array("faults");
            for(int i=fs.length()-1;i>=0&&fh.length()<8;i--){JSONObject o=fs.optJSONObject(i);String h=normalizeSpeechText(o.optString("machine")+" "+o.optString("title")+" "+o.optString("description"));if(keywordOverlap(h,q)>0||(!botContextMachine.isEmpty()&&botContextMachine.equalsIgnoreCase(o.optString("machine"))))fh.put(o);}
            JSONArray dh=new JSONArray(); JSONArray ds=store.array("documents");
            for(int i=ds.length()-1;i>=0&&dh.length()<8;i--){JSONObject o=ds.optJSONObject(i);JSONObject safe=new JSONObject();safe.put("id",o.optString("id"));safe.put("title",o.optString("title"));safe.put("machine",o.optString("machine"));safe.put("createdAt",o.optString("createdAt"));dh.put(safe);}
            out.put("interventions",ih); out.put("faults",fh); out.put("documents",dh);
            JSONObject u=store.currentUser(); JSONArray upcoming=new JSONArray();
            if(u!=null){for(int d=0;d<7;d++){LocalDate date=LocalDate.now().plusDays(d);String code=store.getShift(u.optString("id"),date.toString());if(!code.isEmpty()){JSONObject sh=new JSONObject();sh.put("date",date.toString());sh.put("code",code);upcoming.put(sh);}}}
            out.put("upcoming_shifts",upcoming);
        }catch(Exception ignored){}
        return out;
    }

    private static class MachineMatch {
        String machine="";
        ArrayList<String> ambiguous=new ArrayList<>();
    }

    private MachineMatch findMachineMatch(String x){
        MachineMatch out=new MachineMatch();
        JSONArray a=store.array("machines");
        String compactQ=x.replaceAll("[^a-z0-9]","");
        for(int i=0;i<a.length();i++){
            String n=a.optJSONObject(i).optString("name"); if(n.isEmpty())continue;
            String nn=normalizeSpeechText(n); String compactN=nn.replaceAll("[^a-z0-9]","");
            boolean hit=x.contains(nn)||compactQ.contains(compactN);
            if(!hit && compactQ.contains("2b") && compactN.startsWith("2b")) hit=true;
            if(!hit && compactQ.contains("sas2") && compactN.contains("sas2")) hit=true;
            if(hit) out.ambiguous.add(n);
        }
        // Known speech aliases are useful even before all machines are configured.
        if(out.ambiguous.isEmpty() && (compactQ.contains("2b60")||compactQ.equals("2b")||compactQ.contains("2b"))) out.ambiguous.add("2B60");
        if(out.ambiguous.isEmpty() && compactQ.contains("sas2")) out.ambiguous.add("SAS 2");
        if(out.ambiguous.size()==1) out.machine=out.ambiguous.get(0);
        return out;
    }

    private boolean looksLikeFollowUp(String x) {
        return !x.contains("turno") && !x.contains("ferie") && !x.contains("comunicaz") && x.length()>2;
    }

    private boolean hasSymptom(String x,String machine) {
        String y=x.replace(normalizeSpeechText(machine)," ")
                .replace("2b60"," ").replace("2b"," ").replace("sas2"," ");
        String[] stop={"ho","un","una","il","la","lo","i","gli","le","con","alla","al","sulla","sul","della","del","di","da","problema","guasto","macchina","impianto","linea","che","mi","fa","c e","ce"};
        Set<String> st=new HashSet<>(Arrays.asList(stop));
        int useful=0;
        for(String w:y.split("\\s+")) if(w.length()>2&&!st.contains(w)) useful++;
        return useful>=1;
    }

    private String answerMachineHistory(String machine,String q){
        JSONArray ints=store.array("interventions"); JSONArray faults=store.array("faults");
        ArrayList<String> hits=new ArrayList<>(); ArrayList<String> sources=new ArrayList<>();
        for(int i=ints.length()-1;i>=0&&hits.size()<4;i--){JSONObject in=ints.optJSONObject(i);if(machine.equalsIgnoreCase(in.optString("machine"))){String note=in.optString("note");if(relevant(note,q)){hits.add(note);sources.add("intervento #"+in.optString("id")+" "+in.optString("createdAt"));}}}
        for(int i=faults.length()-1;i>=0&&hits.size()<4;i--){JSONObject f=faults.optJSONObject(i);if(machine.equalsIgnoreCase(f.optString("machine"))){String note=f.optString("title")+" - "+f.optString("description");if(relevant(note,q)){hits.add(note);sources.add("guasto #"+f.optString("id")+" "+f.optString("createdAt"));}}}
        if(hits.isEmpty()) {
            return "Ho capito "+machine+" e il sintomo. Non trovo ancora un caso abbastanza simile nello storico. "+followUpQuestion(q)+" Posso continuare a ragionare con te e, quando avremo dati reali, confronterò automaticamente interventi, foto, schemi e manuali.";
        }
        StringBuilder b=new StringBuilder("Ho trovato casi simili su "+machine+": ");
        for(int i=0;i<hits.size();i++){if(i>0)b.append(" | ");b.append(hits.get(i));}
        b.append(". Fonti: "); for(int i=0;i<sources.size();i++){if(i>0)b.append(", ");b.append(sources.get(i));}
        b.append(". ").append(followUpQuestion(q));
        return b.toString();
    }

    private String followUpQuestion(String q) {
        String x=normalizeSpeechText(q);
        if(x.contains("tagl")||x.contains("cesoia")) return "Il difetto è un taglio incompleto, irregolare oppure la cesoia non parte proprio?";
        if(x.contains("bisell")||x.contains("bisella")) return "La bisella non si muove, va in guasto, non raggiunge la posizione oppure lavora male?";
        if(x.contains("pompa")) return "La pompa non parte, gira senza pressione, perde oppure compare un allarme?";
        if(x.contains("motore")) return "Il motore non parte, si ferma, scalda oppure va in allarme?";
        if(x.contains("inverter")||x.contains("drive")) return "Che marca/modello è e quale codice o messaggio compare sul display del drive?";
        if(x.contains("sensore")||x.contains("finecorsa")||x.contains("fotocell")) return "Il sensore cambia stato sul LED? E l'ingresso corrispondente cambia anche sul PLC o sulla diagnostica macchina?";
        if(x.contains("valvol")||x.contains("elettrovalvol")) return "La bobina riceve tensione quando dovrebbe commutare? La valvola cambia stato manualmente o resta bloccata?";
        if(x.contains("radiocomando")||x.contains("carroponte")||x.contains("gru")) return "Mi dai marca e modello del trasmettitore/ricevitore oppure il codice della targhetta? Per pairing e sicurezza uso la procedura specifica del costruttore.";
        return "Che comportamento preciso vedi, da quando succede e il difetto è continuo o intermittente?";
    }

    private boolean relevant(String note,String q){
        String n=normalizeSpeechText(note); String query=normalizeSpeechText(q);
        String[] words=query.split("\\s+"); int m=0;
        for(String w:words){if(w.length()>3&&!isSearchStopWord(w)&&(n.contains(w)||stemHit(n,w)))m++;}
        return m>0;
    }

    private boolean stemHit(String text,String word) {
        if(word.length()<5)return false;
        String stem=word.substring(0,word.length()-1);
        return stem.length()>=4&&text.contains(stem);
    }

    private boolean isSearchStopWord(String w){
        return Arrays.asList("problema","guasto","macchina","impianto","della","dello","alla","sulla","questo","quella","2b60","sas2").contains(w);
    }

    private String normalizeTechnicalTranscript(String s){
        if(s==null)return"";
        String x=s.trim();
        x=x.replaceAll("(?i)\\bdue\\s+(b|bi)\\s+(sessanta|60|6\\s*0)\\b","2B60");
        x=x.replaceAll("(?i)\\b2\\s*(b|bi)\\s*(sessanta|60|6\\s*0)\\b","2B60");
        x=x.replaceAll("(?i)\\b2b\\s*60\\b","2B60");
        x=x.replaceAll("(?i)\\bs\\s*a\\s*s\\s*(2|due)\\b","SAS 2");
        x=x.replaceAll("(?i)\\bsas\\s*(2|due)\\b","SAS 2");
        x=x.replaceAll("(?i)\\bradio\\s+comando\\b","radiocomando");
        x=x.replaceAll("(?i)\\bcarro\\s+ponte\\b","carroponte");
        x=x.replaceAll("(?i)\\bfine\\s+corsa\\b","finecorsa");
        x=x.replaceAll("(?i)\\bin\\s+verter\\b","inverter");
        x=x.replaceAll("(?i)\\bsoft\\s+starter\\b","softstarter");
        x=x.replaceAll("(?i)\\bprofi\\s+bus\\b","profibus");
        x=x.replaceAll("(?i)\\bprofi\\s+net\\b","profinet");
        x=x.replaceAll("(?i)\\bmod\\s+bus\\b","modbus");
        x=x.replaceAll("(?i)\\belettr[o]?\\s*valvola\\b","elettrovalvola");
        x=x.replaceAll("(?i)\\bp\\s*l\\s*c\\b","PLC");
        x=x.replaceAll("\\s+"," ").trim();
        return x;
    }

    private String normalizeSpeechText(String s){
        if(s==null)return"";
        String x=normalizeTechnicalTranscript(s);
        x=Normalizer.normalize(x,Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ITALY);
        x=x.replaceAll("\\bdue\\s+(b|bi)\\s+(sessanta|60|6\\s*0)\\b","2b60");
        x=x.replaceAll("\\b2\\s*(b|bi)\\s*6\\s*0\\b","2b60");
        x=x.replaceAll("\\b2\\s*(b|bi)\\s*60\\b","2b60");
        x=x.replaceAll("\\b2b\\s*60\\b","2b60");
        x=x.replaceAll("\\b2\\s*b60\\b","2b60");
        x=x.replaceAll("\\bs\\s*a\\s*s\\s*(2|due)\\b","sas2");
        x=x.replaceAll("\\bsas\\s*(2|due)\\b","sas2");
        x=x.replaceAll("\\bradio\\s+comando\\b","radiocomando");
        x=x.replaceAll("\\bin\\s+verter\\b","inverter");
        x=x.replaceAll("\\bp\\s*l\\s*c\\b","plc");
        x=x.replaceAll("[^a-z0-9]+"," ").replaceAll("\\s+"," ").trim();
        return x;
    }

    private String chooseBestSpeechResult(ArrayList<String> results){
        if(results==null||results.isEmpty())return"";
        String best=results.get(0); int bestScore=Integer.MIN_VALUE;
        for(String raw:results){
            if(raw==null)continue;
            String n=normalizeSpeechText(raw); int score=0;
            if(TechnicalKnowledge.isTechnical(n))score+=20;
            if(n.contains("2b60")||n.contains("sas2"))score+=25;
            if(n.contains("inverter")||n.contains("radiocomando")||n.contains("elettrovalvola")||n.contains("plc")||n.contains("finecorsa")||n.contains("carroponte")||n.contains("profibus")||n.contains("profinet"))score+=12;
            JSONArray knownMachines=store==null?new JSONArray():store.array("machines");
            for(int mi=0;mi<knownMachines.length();mi++){JSONObject mo=knownMachines.optJSONObject(mi);if(mo==null)continue;String mn=normalizeSpeechText(mo.optString("name"));if(!mn.isEmpty()&&n.contains(mn))score+=30;}
            JSONArray knownDocs=store==null?new JSONArray():store.array("documents");
            for(int di=Math.max(0,knownDocs.length()-20);di<knownDocs.length();di++){JSONObject d=knownDocs.optJSONObject(di);if(d==null)continue;String title=normalizeSpeechText(d.optString("title"));for(String w:title.split(" "))if(w.length()>4&&n.contains(w))score+=2;}
            if(!botContextMachine.isEmpty()&&looksLikeFollowUp(n))score+=5;
            if(!botAwaiting.isEmpty())score+=3;
            score+=Math.min(10,n.length()/12);
            if(score>bestScore){bestScore=score;best=raw;}
        }
        return normalizeTechnicalTranscript(best);
    }

    private String join(List<String> items){StringBuilder b=new StringBuilder();for(int i=0;i<items.size();i++){if(i>0)b.append(" oppure ");b.append(items.get(i));}return b.toString();}

    private String spokenShift(String code){
        if("1".equals(code))return"turno 1"; if("2".equals(code))return"turno 2"; if("3".equals(code))return"turno 3";
        return "codice "+code;
    }

    private LocalDate resolveDay(String x){LocalDate now=LocalDate.now();String[] it={"lunedi","martedi","mercoledi","giovedi","venerdi","sabato","domenica"};DayOfWeek[] dw={DayOfWeek.MONDAY,DayOfWeek.TUESDAY,DayOfWeek.WEDNESDAY,DayOfWeek.THURSDAY,DayOfWeek.FRIDAY,DayOfWeek.SATURDAY,DayOfWeek.SUNDAY};if(x.contains("domani"))return now.plusDays(1);for(int i=0;i<it.length;i++)if(x.contains(it[i])){LocalDate d=now.with(TemporalAdjusters.nextOrSame(dw[i]));if(d.equals(now)&&!x.contains("oggi"))return d;return d;}return now;}
    private String prettyDate(LocalDate d){return d.getDayOfWeek().getDisplayName(TextStyle.FULL,Locale.ITALIAN)+" "+d.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));}

    private String speechFriendly(String s){
        if(s==null)return"";
        String x=s.replace("\n"," ").replace("•"," ");
        int source=x.toLowerCase(Locale.ITALY).indexOf("fonti:");
        if(source<0)source=x.toLowerCase(Locale.ITALY).indexOf("fonte:");
        if(source>0)x=x.substring(0,source).trim()+". Le fonti sono indicate a schermo.";
        x=x.replaceAll("[\\p{So}]","").replaceAll("\\s+"," ").trim();
        if(x.length()>620)x=x.substring(0,620)+". Se vuoi, continuo con i dettagli.";
        return x;
    }

    private String neuralTtsEndpoint(){
        String e=store.getSetting("ai_endpoint","").trim();
        if(e.isEmpty())return"";
        if(e.endsWith("/skilla"))return e.substring(0,e.length()-7)+"/tts";
        if(e.endsWith("/"))return e+"tts";
        return e+"/tts";
    }

    private void finishVoicePlayback(){
        ttsSpeaking=false;
        if(voiceConversation && store!=null && store.currentUser()!=null) speechHandler.postDelayed(this::startVoiceCommand,650);
        else scheduleWake(550);
    }

    private void speakWithSystem(String text){
        if(tts!=null){ stopSpeechRecognizer(); ttsSpeaking=true; tts.speak(text,TextToSpeech.QUEUE_FLUSH,null,"valman_bot"); }
        else finishVoicePlayback();
    }

    private void speak(String s){
        if(!store.getBoolSetting("voice_replies",true)){
            if(voiceConversation)speechHandler.postDelayed(this::startVoiceCommand,550); else scheduleWake(550);
            return;
        }
        final String spoken=speechFriendly(s);
        final String endpoint=neuralTtsEndpoint();
        if(store.getBoolSetting("neural_voice",true)&&!endpoint.isEmpty()){
            stopSpeechRecognizer(); ttsSpeaking=true;
            AiGateway.synthesize(endpoint,spoken,getCacheDir(),tokenVault==null?"":tokenVault.load(),mainHandler,new AiGateway.AudioCallback(){
                @Override public void onSuccess(File audioFile){
                    try{
                        if(neuralPlayer!=null){try{neuralPlayer.release();}catch(Exception ignored){}}
                        neuralPlayer=new MediaPlayer();
                        neuralPlayer.setDataSource(audioFile.getAbsolutePath());
                        neuralPlayer.setOnCompletionListener(mp->{ try{mp.release();}catch(Exception ignored){} neuralPlayer=null; try{audioFile.delete();}catch(Exception ignored){} finishVoicePlayback(); });
                        neuralPlayer.setOnErrorListener((mp,what,extra)->{try{mp.release();}catch(Exception ignored){} neuralPlayer=null;try{audioFile.delete();}catch(Exception ignored){} speakWithSystem(spoken);return true;});
                        neuralPlayer.prepare(); neuralPlayer.start();
                    }catch(Exception e){ try{audioFile.delete();}catch(Exception ignored){} speakWithSystem(spoken); }
                }
                @Override public void onError(String message){ speakWithSystem(spoken); }
            });
            return;
        }
        speakWithSystem(spoken);
    }

    // Manual command: use the phone's full speech-recognition activity.
    // This is much more reliable on Honor/Huawei devices than reusing a continuously-running SpeechRecognizer.
    private void startVoiceCommand(){
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},55);return;}
        stopSpeechRecognizer();
        Intent i=speechIntent();
        i.putExtra(RecognizerIntent.EXTRA_PROMPT,"Parla normalmente. Skilla riconosce termini tecnici e codici macchina.");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,false);
        try{
            startActivityForResult(i,REQ_SPEECH);
        }catch(ActivityNotFoundException e){
            // Fallback for phones without a speech-recognition activity.
            ensureSpeechRecognizer();
            speechMode="COMMAND";
            speechHandler.postDelayed(this::startRecognizerNow,300);
            toast("Skilla ti ascolta…");
        }
    }

    private void ensureSpeechRecognizer(){
        if(speechRecognizer!=null)return;
        if(!SpeechRecognizer.isRecognitionAvailable(this))return;
        try{
            if(Build.VERSION.SDK_INT>=31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this))
                speechRecognizer=SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
            else speechRecognizer=SpeechRecognizer.createSpeechRecognizer(this);
        }catch(Exception e){ speechRecognizer=SpeechRecognizer.createSpeechRecognizer(this); }
        speechRecognizer.setRecognitionListener(new RecognitionListener(){
            @Override public void onReadyForSpeech(Bundle params){}
            @Override public void onBeginningOfSpeech(){}
            @Override public void onRmsChanged(float rmsdB){}
            @Override public void onBufferReceived(byte[] buffer){}
            @Override public void onEndOfSpeech(){}
            @Override public void onError(int error){
                speechBusy=false;
                if("COMMAND".equals(speechMode)){
                    speechMode="WAKE";
                    // Inline fallback failed: open the system recognizer instead of showing a dead-end toast.
                    speechHandler.postDelayed(()->{ if(activityForeground) startVoiceCommand(); },350);
                    return;
                }
                long wait=(error==SpeechRecognizer.ERROR_RECOGNIZER_BUSY||error==SpeechRecognizer.ERROR_CLIENT)?1600:900;
                scheduleWake(wait);
            }
            @Override public void onResults(Bundle results){
                speechBusy=false;
                if(wakeConsumed){ wakeConsumed=false; return; }
                ArrayList<String> list=results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String heard=chooseBestSpeechResult(list);
                if(!heard.isEmpty())handleRecognizedSpeech(heard);else scheduleWake(800);
            }
            @Override public void onPartialResults(Bundle partialResults){
                if(wakeConsumed)return;
                ArrayList<String> list=partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if(list==null||list.isEmpty())return;
                String heard=chooseBestSpeechResult(list);
                String n=normalizeSpeechText(heard);
                if(n.contains("hey skilla")||n.contains("ehi skilla")||n.contains("hei skilla")||n.contains("ok skilla")){
                    wakeConsumed=true; speechBusy=false;
                    try{speechRecognizer.cancel();}catch(Exception ignored){}
                    final String finalHeard=heard;
                    speechHandler.post(()->handleRecognizedSpeech(finalHeard));
                }
            }
            @Override public void onEvent(int eventType,Bundle params){}
        });
    }

    private Intent speechIntent(){
        Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"it-IT");
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,"it-IT");
        i.putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE,false);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,8);
        i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,false);
        return i;
    }

    private void startRecognizerNow(){
        if(!activityForeground||ttsSpeaking||store.currentUser()==null||checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)return;
        ensureSpeechRecognizer(); if(speechRecognizer==null)return;
        try{wakeConsumed=false;speechRecognizer.cancel();speechHandler.postDelayed(()->{try{if(speechRecognizer!=null&&activityForeground&&!ttsSpeaking){speechRecognizer.startListening(speechIntent());speechBusy=true;}}catch(Exception ignored){speechBusy=false;}},180);}catch(Exception e){speechBusy=false;}
    }

    private void scheduleWake(long delay){
        speechHandler.removeCallbacksAndMessages(null);
        if(!activityForeground||ttsSpeaking||store==null||store.currentUser()==null||checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)return;
        speechMode="WAKE"; speechHandler.postDelayed(this::startRecognizerNow,delay);
    }

    private void stopSpeechRecognizer(){
        speechHandler.removeCallbacksAndMessages(null); speechBusy=false;
        if(speechRecognizer!=null){try{speechRecognizer.cancel();}catch(Exception ignored){}}
    }

    private void handleRecognizedSpeech(String heard){
        String n=normalizeSpeechText(heard);
        if("COMMAND".equals(speechMode)){
            speechMode="WAKE"; if(botMessages==null)showBot(); if(botInput!=null)botInput.setText(heard); submitBotQuery(heard); return;
        }
        int pos=n.indexOf("hey skilla"); int len="hey skilla".length();
        if(pos<0){pos=n.indexOf("ehi skilla");len="ehi skilla".length();}
        if(pos<0){pos=n.indexOf("hei skilla");len="hei skilla".length();}
        if(pos<0){pos=n.indexOf("ok skilla");len="ok skilla".length();}
        if(pos>=0){
            voiceConversation=true;
            String cmd=n.substring(Math.min(n.length(),pos+len)).trim();
            showBot();
            if(!cmd.isEmpty()) submitBotQuery(cmd);
            else startVoiceCommand();
        } else scheduleWake(350);
    }

    private void showHandovers(){LinearLayout r=screen();addHeader(r,"Consegne turno",this::showDashboard);EditText n=multiInput("Nota per il turno successivo...");r.addView(n);Button add=button("Aggiungi consegna",C_BLUE);r.addView(add);add.setOnClickListener(v->{if(str(n).isEmpty())return;store.addHandover(store.currentUserId(),str(n));showHandovers();});JSONArray a=store.array("handovers");for(int i=a.length()-1;i>=0;i--){JSONObject o=a.optJSONObject(i);LinearLayout c=card();c.addView(text(o.optString("createdAt")+" • "+o.optString("userId"),13,C_ORANGE,true));c.addView(text(o.optString("text"),15,C_TEXT,false));r.addView(c);}}

    private void showLeaves(){LinearLayout r=screen();addHeader(r,"Ferie / PAR",this::showDashboard);Button add=button("+ Nuova richiesta / pianificazione",C_BLUE);r.addView(add);add.setOnClickListener(v->showNewLeave());CalendarView cal=new CalendarView(this);cal.setBackgroundColor(Color.WHITE);r.addView(cal,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(300)));r.addView(label("Assenze inserite"));JSONArray a=store.array("leaves");if(a.length()==0)r.addView(sub("Nessuna assenza inserita."));for(int i=a.length()-1;i>=0;i--){JSONObject o=a.optJSONObject(i);LinearLayout c=card();c.addView(text(o.optString("name")+" • "+o.optString("type"),16,C_TEXT,true));c.addView(sub(o.optString("start")+" → "+o.optString("end")+" • "+o.optString("status")));if(!o.optString("note").isEmpty())c.addView(sub(o.optString("note")));if(store.isAdmin(store.currentUser())){final String id=o.optString("id");c.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Gestisci richiesta").setItems(new String[]{"Approva","Rifiuta","Lascia inserita"},(d,w)->{store.setLeaveStatus(id,w==0?"APPROVATA":w==1?"RIFIUTATA":"INSERITA");showLeaves();}).show());}r.addView(c);}}

    private void showNewLeave(){LinearLayout r=screen();addHeader(r,"Nuova Ferie / PAR",this::showLeaves);Spinner type=simpleSpinner(Arrays.asList("FERIE","PAR"));Button start=button("Data inizio",C_PANEL2);Button end=button("Data fine",C_PANEL2);final String[] dates={LocalDate.now().toString(),LocalDate.now().toString()};start.setText("Inizio: "+dates[0]);end.setText("Fine: "+dates[1]);start.setOnClickListener(v->pickDate(d->{dates[0]=d;start.setText("Inizio: "+d);}));end.setOnClickListener(v->pickDate(d->{dates[1]=d;end.setText("Fine: "+d);}));EditText note=input("Nota opzionale");r.addView(type);r.addView(start);r.addView(end);r.addView(note);Button save=button("Inserisci nel calendario",C_GREEN);r.addView(save);save.setOnClickListener(v->{JSONObject u=store.currentUser();store.addLeave(u.optString("id"),u.optString("name"),String.valueOf(type.getSelectedItem()),dates[0],dates[1],str(note));showLeaves();});}
    interface DateCb{void onDate(String d);}private void pickDate(final DateCb cb){Calendar c=Calendar.getInstance();new DatePickerDialog(this,(v,y,m,d)->cb.onDate(String.format(Locale.ITALY,"%04d-%02d-%02d",y,m+1,d)),c.get(Calendar.YEAR),c.get(Calendar.MONTH),c.get(Calendar.DAY_OF_MONTH)).show();}

    private void showTurnation(){turnationWeek=LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));showTurnationWeek();}
    private void showTurnationWeek(){
        LinearLayout r=screen(); addHeader(r,"Turnazione",this::showDashboard);
        LinearLayout nav=new LinearLayout(this); Button prev=button("‹",C_PANEL2); Button next=button("›",C_PANEL2);
        TextView w=text("Settimana "+turnationWeek.format(DateTimeFormatter.ofPattern("dd/MM"))+" - "+turnationWeek.plusDays(6).format(DateTimeFormatter.ofPattern("dd/MM/yyyy")),16,C_TEXT,true); w.setGravity(Gravity.CENTER);
        nav.addView(prev,new LinearLayout.LayoutParams(dp(60),dp(50))); nav.addView(w,new LinearLayout.LayoutParams(0,dp(50),1)); nav.addView(next,new LinearLayout.LayoutParams(dp(60),dp(50))); r.addView(nav);
        prev.setOnClickListener(v->{turnationWeek=turnationWeek.minusWeeks(1);showTurnationWeek();}); next.setOnClickListener(v->{turnationWeek=turnationWeek.plusWeeks(1);showTurnationWeek();});

        String imageUri=store.turnationImage(turnationWeek.toString());
        if(!imageUri.isEmpty()){
            ImageView im=new ImageView(this); im.setAdjustViewBounds(true); im.setMaxHeight(dp(390));
            try{im.setImageURI(Uri.parse(imageUri));}catch(Exception ignored){} r.addView(im);
            String raw=store.turnationOcrText(turnationWeek.toString());
            if(!raw.isEmpty()) r.addView(sub("✓ Foto analizzata con OCR. I turni pubblicati sono quelli verificati dall'amministratore."));
        }
        if(store.isAdmin(store.currentUser())){
            Button img=button("📷 Carica e riconosci turnazione",C_BLUE); r.addView(img); img.setOnClickListener(v->pickFile("image/*",REQ_TURNATION_IMAGE));
            if(!imageUri.isEmpty()){
                Button retry=button("🔎 Analizza di nuovo la foto",C_PANEL2); r.addView(retry); retry.setOnClickListener(v->analyzeTurnationImage(Uri.parse(imageUri)));
            }
            Button edit=button("✏ Correggi turni manualmente",C_ORANGE); r.addView(edit); edit.setOnClickListener(v->showShiftEditor());
        }
        JSONObject u=store.currentUser(); LinearLayout c=card(); c.addView(text("Il mio turno",18,C_TEXT,true));
        for(int i=0;i<7;i++){LocalDate d=turnationWeek.plusDays(i);String code=store.getShift(u.optString("id"),d.toString());c.addView(text(d.getDayOfWeek().getDisplayName(TextStyle.SHORT,Locale.ITALIAN)+" "+d.getDayOfMonth()+"   "+(code.isEmpty()?"—":code),16,code.isEmpty()?C_MUTED:C_TEXT,!code.isEmpty()));}
        r.addView(c);
        r.addView(sub("La foto originale viene sempre conservata. L'OCR prepara solo una bozza: nessun turno viene pubblicato finché l'admin non lo verifica e conferma."));
    }

    private void analyzeTurnationImage(Uri uri){
        toast("Analisi del settimanale in corso…");
        pendingOcrImageUri=uri==null?"":uri.toString();
        pendingOcrWeek=null;
        TurnationOcr.analyze(this,uri,store.rosterUsers(),new TurnationOcr.Callback(){
            @Override public void onSuccess(List<TurnationOcr.Row> rows,String rawText,boolean headerFound,String detectedWeekStart){
                pendingOcrRows=rows; pendingOcrRaw=rawText;
                if(detectedWeekStart!=null&&!detectedWeekStart.isEmpty()){
                    try{pendingOcrWeek=LocalDate.parse(detectedWeekStart);}catch(Exception ignored){pendingOcrWeek=null;}
                }
                runOnUiThread(()->showOcrReview(headerFound));
            }
            @Override public void onError(String message){runOnUiThread(()->new AlertDialog.Builder(MainActivity.this).setTitle("OCR turnazione").setMessage(message+"\n\nPuoi riprovare con una foto più frontale oppure impostare i turni manualmente.").setPositiveButton("OK",null).show());}
        });
    }

    private void showOcrReview(boolean headerFound){
        final LocalDate reviewWeek=pendingOcrWeek!=null?pendingOcrWeek:turnationWeek;
        LinearLayout r=screen(); addHeader(r,"Verifica turnazione",this::showTurnationWeek);
        LinearLayout info=card(); info.addView(text(headerFound?"OCR completato":"OCR parziale",16,headerFound?C_GREEN:C_ORANGE,true));
        if(pendingOcrWeek!=null){
            info.addView(text("Settimana riconosciuta: "+reviewWeek.format(DateTimeFormatter.ofPattern("dd/MM"))+" - "+reviewWeek.plusDays(6).format(DateTimeFormatter.ofPattern("dd/MM/yyyy")),15,C_TEXT,true));
            if(!reviewWeek.equals(turnationWeek)) info.addView(sub("La foto appartiene a una settimana diversa da quella aperta. Verrà archiviata automaticamente nella settimana corretta."));
        }else{
            info.addView(sub("Non sono riuscito a leggere con certezza le date del foglio: userò la settimana che avevi aperto."));
        }
        info.addView(sub("Controlla ogni riga prima di pubblicare. ValMan ricostruisce ora la riga cella per cella usando posizione del dipendente e colonne del settimanale. Controlla comunque i valori prima di pubblicare.")); r.addView(info);
        if(pendingOcrRows.isEmpty()){
            r.addView(sub("Non ho trovato nessun nome che corrisponda agli utenti ValMan. Crea prima gli ID con nome e cognome corretti oppure usa la correzione manuale."));
            Button back=button("Torna alla turnazione",C_BLUE);r.addView(back);back.setOnClickListener(v->showTurnationWeek());return;
        }
        final ArrayList<EditText[]> editors=new ArrayList<>(); final ArrayList<TurnationOcr.Row> rowsToSave=new ArrayList<>();
        for(TurnationOcr.Row row:pendingOcrRows){
            LinearLayout c=card(); c.addView(text(row.name+" • "+row.recognized+"/7 letti",16,C_TEXT,true));
            if(!row.nameMatched)c.addView(text("Nome non riconosciuto con sicurezza",13,C_ORANGE,true));
            if(!row.raw.isEmpty())c.addView(sub("Riga OCR: "+row.raw));
            EditText[] ed=new EditText[7];
            for(int d=0;d<7;d++){LocalDate date=reviewWeek.plusDays(d);ed[d]=input(date.getDayOfWeek().getDisplayName(TextStyle.SHORT,Locale.ITALIAN)+" "+date.getDayOfMonth()+" • turno");ed[d].setText(row.codes[d]);c.addView(ed[d]);}
            editors.add(ed); rowsToSave.add(row); r.addView(c);
        }
        Button publish=button("✓ Conferma e pubblica turni",C_GREEN);r.addView(publish);
        publish.setOnClickListener(v->{
            for(int i=0;i<rowsToSave.size();i++){TurnationOcr.Row row=rowsToSave.get(i);EditText[] ed=editors.get(i);for(int d=0;d<7;d++)store.setShift(row.userId,reviewWeek.plusDays(d).toString(),str(ed[d]));}
            if(!pendingOcrImageUri.isEmpty())store.setTurnationImage(reviewWeek.toString(),pendingOcrImageUri);
            store.setTurnationOcrText(reviewWeek.toString(),pendingOcrRaw);
            turnationWeek=reviewWeek;
            toast("Turnazione verificata e pubblicata nella settimana corretta."); showTurnationWeek();
        });
    }

    private void showShiftEditor(){LinearLayout r=screen();addHeader(r,"Imposta turni",this::showTurnationWeek);JSONArray users=store.rosterUsers();ArrayList<String> labels=new ArrayList<>();ArrayList<String> ids=new ArrayList<>();for(int i=0;i<users.length();i++){JSONObject u=users.optJSONObject(i);if(u.optBoolean("enabled",true)){labels.add(u.optString("name")+" ("+u.optString("id")+")");ids.add(u.optString("id"));}}Spinner us=simpleSpinner(labels);r.addView(us);final EditText[] ed=new EditText[7];for(int i=0;i<7;i++){LocalDate d=turnationWeek.plusDays(i);ed[i]=input(d.getDayOfWeek().getDisplayName(TextStyle.FULL,Locale.ITALIAN)+" "+d.getDayOfMonth()+" • codice turno");r.addView(ed[i]);}Button load=button("Carica turni esistenti",C_PANEL2);r.addView(load);load.setOnClickListener(v->{if(ids.isEmpty())return;String id=ids.get(us.getSelectedItemPosition());for(int i=0;i<7;i++)ed[i].setText(store.getShift(id,turnationWeek.plusDays(i).toString()));});Button save=button("Salva settimana",C_GREEN);r.addView(save);save.setOnClickListener(v->{if(ids.isEmpty())return;String id=ids.get(us.getSelectedItemPosition());for(int i=0;i<7;i++)store.setShift(id,turnationWeek.plusDays(i).toString(),str(ed[i]));showTurnationWeek();});}

    private void showCommunications(){LinearLayout r=screen();addHeader(r,"Comunicazioni",this::showDashboard);if(store.isAdmin(store.currentUser())){Button add=button("+ Nuova comunicazione",C_BLUE);r.addView(add);add.setOnClickListener(v->showNewCommunication());}JSONArray a=store.array("communications");if(a.length()==0)r.addView(sub("Nessuna comunicazione."));for(int i=a.length()-1;i>=0;i--){JSONObject o=a.optJSONObject(i);LinearLayout c=card();c.addView(text(o.optString("type")+" • "+o.optString("title"),17,C_TEXT,true));c.addView(sub(o.optString("when")+" • Destinatari: "+o.optString("target")));c.addView(text(o.optString("body"),14,C_TEXT,false));r.addView(c);}}
    private void showNewCommunication(){LinearLayout r=screen();addHeader(r,"Nuova comunicazione",this::showCommunications);Spinner type=simpleSpinner(Arrays.asList("AVVISO","CORSO","VISITA","RIUNIONE","SICUREZZA"));EditText title=input("Titolo");EditText when=input("Data / ora (es. 12/12/2026 08:00)");EditText target=input("Destinatari (es. Elettrici + Meccanici)");EditText body=multiInput("Dettagli...");r.addView(type);r.addView(title);r.addView(when);r.addView(target);r.addView(body);Button save=button("Pubblica",C_GREEN);r.addView(save);save.setOnClickListener(v->{if(str(title).isEmpty())return;store.addCommunication(String.valueOf(type.getSelectedItem()),str(title),str(when),str(body),str(target));showCommunications();});}

    private void showDocuments(){LinearLayout r=screen();addHeader(r,"Documenti",this::showDashboard);Button add=button("+ Aggiungi schema / manuale",C_BLUE);r.addView(add);add.setOnClickListener(v->pickFile("*/*",REQ_DOCUMENT));JSONArray a=store.array("documents");if(a.length()==0)r.addView(sub("Nessun documento allegato."));for(int i=a.length()-1;i>=0;i--){JSONObject o=a.optJSONObject(i);LinearLayout c=card();c.addView(text(o.optString("title"),16,C_TEXT,true));c.addView(sub(o.optString("machine")+" • "+o.optString("createdAt")));final String uri=o.optString("uri"),mime=o.optString("mime");c.setOnClickListener(v->openUri(uri,mime));r.addView(c);}}

    private void showAdmin(){
        LinearLayout r=screen(); addHeader(r,"Amministrazione",this::showDashboard);
        Button server=button("☁ Supabase & sincronizzazione",C_CYAN);r.addView(server);server.setOnClickListener(v->showServerSettings());
        Button user=button("+ Crea ID dipendente",C_BLUE); r.addView(user); user.setOnClickListener(v->showAddUser());
        Button staff=button("👥 Personale manutenzione",C_PANEL2);r.addView(staff);staff.setOnClickListener(v->showStaffDirectory());
        Button ai=button("🤖 Impostazioni Skilla Bot",C_CYAN); r.addView(ai); ai.setOnClickListener(v->showSkillaSettings());
        Button sync=button("↻ Sincronizza adesso",C_PANEL2);r.addView(sync);sync.setEnabled(remoteReady());sync.setOnClickListener(v->runRemoteSync(true));
        Button demo=button("Carica dati DEMO Skilla Bot",C_ORANGE); r.addView(demo); demo.setOnClickListener(v->{store.addDemoData(store.currentUserId());toast("Dati demo caricati (marcati DEMO).");showAdmin();});
        r.addView(label("Utenti"));
        JSONArray a=store.array("users");
        for(int i=0;i<a.length();i++){
            JSONObject u=a.optJSONObject(i); LinearLayout c=card();
            c.addView(text(u.optString("name")+" • "+u.optString("id"),16,C_TEXT,true));
            c.addView(sub(roleLabel(u.optString("role"))+" • "+(u.optBoolean("enabled",true)?"Attivo":"Disabilitato")+(u.optString("passwordHash").isEmpty()?" • Primo accesso":"")));
            if(!"ADMIN".equals(u.optString("role"))){final String id=u.optString("id");final boolean currentEnabled=u.optBoolean("enabled",true);c.setOnClickListener(v->{
                if(remoteReady()){
                    RemoteApi.setUserEnabled(remoteBase(),tokenVault.load(),id,!currentEnabled,mainHandler,new RemoteApi.Callback(){
                        @Override public void ok(JSONObject data){store.setUserEnabled(id,!currentEnabled);runRemoteSync(false);showAdmin();}
                        @Override public void error(int code,String message,JSONObject data){toast("Modifica non riuscita: "+message);}
                    });
                }else{store.toggleUserEnabled(id);showAdmin();}
            });}
            r.addView(c);
        }
        LinearLayout warn=card(); warn.addView(text(remoteReady()?"Modalità condivisa Supabase attiva":"Modalità locale • Supabase non autenticato",14,remoteReady()?C_GREEN:C_ORANGE,true));
        warn.addView(sub(remoteReady()?"I dati vengono sincronizzati su Supabase. In assenza di rete continui a lavorare sulla copia locale e ValMan ritenta appena possibile.":"Effettua l'accesso a Supabase per condividere dati e account tra i telefoni. La chiave AI non è contenuta nell'APK."));
        r.addView(warn);
    }

    private void showServerSettings(){
        Runnable back=store.currentUser()==null?this::showLogin:this::showAdmin;
        LinearLayout r=screen();addHeader(r,"Supabase & sincronizzazione",back);
        LinearLayout st=card();String last=store.getSetting("last_sync","");
        st.addView(text(remoteReady()?"Supabase collegato":"Supabase pronto • accesso richiesto",18,remoteReady()?C_GREEN:C_ORANGE,true));
        st.addView(sub(SupabaseConfig.PROJECT_URL+(last.isEmpty()?"":"\nUltima sincronizzazione: "+last)));r.addView(st);

        Button test=button("Test connessione",C_PANEL2);r.addView(test);test.setOnClickListener(v->RemoteApi.health(remoteBase(),mainHandler,new RemoteApi.Callback(){@Override public void ok(JSONObject data){toast("Supabase ValMan raggiungibile.");}@Override public void error(int code,String message,JSONObject data){toast("Supabase non raggiungibile: "+message);}}));

        JSONObject current=store.currentUser();
        EditText loginId=input("ID o email del mio account");
        if(current!=null)loginId.setText(current.optString("authLogin",current.optString("id")));
        EditText loginPw=input("Password del mio account");loginPw.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);r.addView(loginId);r.addView(loginPw);
        Button connect=button("Accedi / rinnova collegamento",C_GREEN);r.addView(connect);connect.setOnClickListener(v->{String who=str(loginId);if(who.isEmpty()||str(loginPw).isEmpty()){toast("Inserisci ID/email e password.");return;}performRemoteLogin(who,str(loginPw));});

        Button now=button("Sincronizza ora",C_BLUE);r.addView(now);now.setEnabled(remoteReady());now.setOnClickListener(v->runRemoteSync(true));
        Button disconnect=button("Disconnetti questo telefono da Supabase",C_PANEL2);r.addView(disconnect);disconnect.setOnClickListener(v->{tokenVault.clear();store.setSetting("last_sync","");toast("Sessione rimossa. I dati locali restano sul telefono.");showServerSettings();});

        LinearLayout info=card();info.addView(text("Sicurezza",14,C_CYAN,true));info.addView(sub("Nell'APK è presente solo la Publishable key di Supabase. Nessuna Secret key, service_role o password del database viene salvata nell'app."));r.addView(info);
    }

    private void showSkillaSettings(){
        LinearLayout r=screen(); addHeader(r,"Impostazioni Skilla Bot",this::showAdmin);
        LinearLayout status=card();
        String endpoint=store.getSetting("ai_endpoint","").trim();
        status.addView(text(endpoint.isEmpty()?"AI tecnica online non collegata":"AI tecnica online collegata",17,endpoint.isEmpty()?C_ORANGE:C_GREEN,true));
        status.addView(sub("Le funzioni locali (turni, storico, OCR, domande tecniche base e documenti) funzionano comunque. L'endpoint privato abilita ragionamento tecnico generale e ricerca Web dei manuali."));
        r.addView(status);

        EditText ep=input("Endpoint HTTPS del server Skilla AI"); ep.setText(endpoint); r.addView(ep);
        r.addView(sub("Esempio: https://server-azienda.example.com/skilla. Non inserire qui una chiave API."));

        Switch voice=new Switch(this); voice.setText("Risposte vocali"); voice.setTextColor(C_TEXT); voice.setTextSize(16); voice.setChecked(store.getBoolSetting("voice_replies",true)); voice.setPadding(dp(4),dp(12),dp(4),dp(12)); r.addView(voice);
        Switch natural=new Switch(this); natural.setText("Preferisci la voce migliore del telefono"); natural.setTextColor(C_TEXT); natural.setTextSize(16); natural.setChecked(store.getBoolSetting("natural_voice",true)); natural.setPadding(dp(4),dp(12),dp(4),dp(12)); r.addView(natural);
        Switch neural=new Switch(this); neural.setText("Voce neurale dal server quando disponibile"); neural.setTextColor(C_TEXT); neural.setTextSize(16); neural.setChecked(store.getBoolSetting("neural_voice",true)); neural.setPadding(dp(4),dp(12),dp(4),dp(12)); r.addView(neural);
        r.addView(sub("Voce di fallback selezionata dal telefono: "+selectedVoiceName));

        Button save=button("Salva impostazioni",C_GREEN); r.addView(save);
        save.setOnClickListener(v->{
            String url=str(ep);
            if(!url.isEmpty()&&!url.startsWith("https://")){toast("Usa un endpoint HTTPS.");return;}
            store.setSetting("ai_endpoint",url);
            store.setBoolSetting("voice_replies",voice.isChecked());
            store.setBoolSetting("natural_voice",natural.isChecked());
            store.setBoolSetting("neural_voice",neural.isChecked());
            configureBestItalianVoice();
            toast("Impostazioni salvate."); showSkillaSettings();
        });

        LinearLayout scope=card(); scope.addView(text("Regole dell'assistente",14,C_CYAN,true));
        scope.addView(sub("Skilla Bot risponde su meccanica, elettrica/elettronica, automazione, idraulica/pneumatica, officina, manuali, sicurezza e dati ValMan. Le richieste estranee vengono rifiutate cordialmente. Non invia comandi a PLC, gru o macchine."));
        r.addView(scope);
    }

    private void showAddUser(){
        LinearLayout r=screen();addHeader(r,"Nuovo dipendente",this::showAdmin);
        EditText id=input("ID dipendente");EditText name=input("Nome visualizzato");Spinner role=simpleSpinner(Arrays.asList("MECCANICO","ELETTRICO","LETTURA"));
        r.addView(id);r.addView(name);r.addView(role);Button save=button("Crea ID e codice di attivazione",C_GREEN);r.addView(save);
        save.setOnClickListener(v->{
            String uid=str(id).toUpperCase(Locale.ITALY),nm=str(name),rl=String.valueOf(role.getSelectedItem());
            if(uid.length()<2||nm.isEmpty()){toast("Compila ID e nome.");return;}
            if(store.userIdExists(uid)){toast("ID già esistente.");return;}
            if(!remoteReady()){toast("Accedi prima a Supabase dall'area sincronizzazione.");return;}
            RemoteApi.createUser(remoteBase(),tokenVault.load(),uid,nm,rl,mainHandler,new RemoteApi.Callback(){
                @Override public void ok(JSONObject data){JSONObject u=data.optJSONObject("user");if(u!=null)store.cacheRemoteUser(u,"");String code=data.optString("activationCode","");runRemoteSync(false);new AlertDialog.Builder(MainActivity.this).setTitle("ID creato").setMessage("Dipendente: "+uid+"\nCodice di attivazione: "+code+"\n\nComunica ID e codice al dipendente. La password la sceglierà lui al primo accesso.").setPositiveButton("OK",(d,w)->showAdmin()).show();}
                @Override public void error(int code,String message,JSONObject data){toast("Creazione non riuscita: "+message);}
            });
        });
    }

    private void showStaffDirectory(){
        LinearLayout r=screen();addHeader(r,"Personale manutenzione",this::showAdmin);
        JSONArray a=store.array("staffDirectory");
        if(a.length()==0){r.addView(sub("Anagrafica non ancora sincronizzata."));return;}
        String lastTeam="";
        for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o==null||!o.optBoolean("active",true))continue;String team=o.optString("team","");if(!team.equals(lastTeam)){r.addView(label(teamLabel(team)));lastTeam=team;}LinearLayout c=card();String badge=o.optString("badge_code","");if("null".equalsIgnoreCase(badge))badge="";c.addView(text(o.optString("roster_name"),16,C_TEXT,true));c.addView(sub(roleStaffLabel(o.optString("trade"))+(badge.isEmpty()?"":" • Cart. "+badge)));r.addView(c);}
    }
    private String teamLabel(String t){if("MECCANICA".equals(t))return"Meccanica";if("ELETTRICA".equals(t))return"Elettrica / Elettronica";return"Responsabili";}
    private String roleStaffLabel(String t){if("CAPO_REPARTO".equals(t))return"Capo reparto";if("CAPO_SQUADRA".equals(t))return"Capo squadra";if("ELETTRICO".equals(t))return"Man. elettrico/elettronico";return"Man. meccanico";}

    private void showProfile(){LinearLayout r=screen();addHeader(r,"Profilo",this::showDashboard);JSONObject u=store.currentUser();LinearLayout c=card();c.addView(text(u.optString("name"),20,C_TEXT,true));c.addView(sub("ID: "+u.optString("id")));c.addView(sub("Ruolo: "+roleLabel(u.optString("role"))));r.addView(c);}

    private void pickFile(String type,int req){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType(type);startActivityForResult(i,req);}
    private void openUri(String uri,String mime){try{Intent i=new Intent(Intent.ACTION_VIEW);i.setDataAndType(Uri.parse(uri),mime==null||mime.isEmpty()?"*/*":mime);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(i);}catch(Exception e){toast("Nessuna app disponibile per aprire il file.");}}

    @Override
    protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==REQ_SPEECH){
            speechMode="WAKE";
            if(resultCode==RESULT_OK && data!=null){
                ArrayList<String> results=data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                String heard=chooseBestSpeechResult(results);
                if(!heard.isEmpty()){
                    if(botMessages==null)showBot();
                    if(botInput!=null)botInput.setText(heard);
                    submitBotQuery(heard);
                }else { voiceConversation=false; toast("Non ho ricevuto parole. Tocca il microfono e riprova."); }
            } else {
                voiceConversation=false;
                scheduleWake(700);
            }
            return;
        }
        if(resultCode!=RESULT_OK||data==null)return;
        Uri uri=data.getData(); if(uri==null)return;
        try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
        String u=uri.toString();
        if(requestCode==REQ_TURNATION_IMAGE){analyzeTurnationImage(uri);}
        else if(requestCode==REQ_FAULT_PHOTO){store.updateFault(activeFaultId,null,null,u);showFaultDetail(activeFaultId);}
        else if(requestCode==REQ_INTERVENTION_PHOTO){pendingInterventionPhoto=u;toast("Foto allegata.");}
        else if(requestCode==REQ_DOCUMENT){askDocumentMeta(u,data.getType());}
    }

    private void askDocumentMeta(final String uri,final String mime){LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(18),0,dp(18),0);EditText title=input("Titolo documento");Spinner mach=machineSpinner();box.addView(title);box.addView(mach);new AlertDialog.Builder(this).setTitle("Nuovo documento").setView(box).setPositiveButton("Salva",(d,w)->{String t=str(title);if(t.isEmpty())t="Documento "+AppStore.shortId();store.addDocument(t,String.valueOf(mach.getSelectedItem()),uri,mime==null?"*/*":mime);showDocuments();}).setNegativeButton("Annulla",null).show();}

    @Override
    public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if(requestCode==55&&grantResults.length>0&&grantResults[0]==PackageManager.PERMISSION_GRANTED){
            voiceConversation=true; showBot(); startVoiceCommand();
        }
    }
}
