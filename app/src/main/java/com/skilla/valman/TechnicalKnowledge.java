package com.skilla.valman;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Offline first-pass technical knowledge and domain guard.
 * Unknown technical questions are delegated to the private AI backend when configured.
 */
public final class TechnicalKnowledge {
    private TechnicalKnowledge() {}

    private static final Set<String> SOCIAL = new HashSet<>(Arrays.asList(
            "ciao","buongiorno","buonasera","salve","grazie","prego","come stai","tutto bene","come va","bravo","perfetto"
    ));

    private static final String[] OFF_TOPIC = {
            "carbonara","ricetta","cucin","calcio","serie a","champions","film","netflix","gossip","oroscop","lotteria",
            "scommess","fantacalcio","videogioc","fortnite","pokemon","ristorante","cocktail","vacanza dove","meteo"
    };

    private static final String[] TECH = {
            "meccanic","elettric","elettronic","automaz","plc","inverter","azionament","motore","encoder","resolver","sensore",
            "finecorsa","fotocell","proximity","prossimit","elettrovalvol","valvol","pneumatic","idraulic","pression","pompa",
            "cilindr","accumulator","cesoia","bisell","mola","riduttore","cuscinet","boccol","ingranagg","catena","cinghia",
            "sald","torn","fres","foratur","filett","maniglia","copertura","lamiera","gru","carroponte","radiocomando",
            "contattore","rele","relè","fusibile","magnetoterm","differenzial","24v","400v","230v","canopen","profibus",
            "profinet","ethercat","modbus","ethernet ip","siemens","abb","schneider","sew","lenze","danfoss","omron",
            "pilz","sick","pepperl","balluff","ifm","manuale","schema","datasheet","morsett","bobina","solenoide","termica",
            "guasto","allarme","errore","fault","trip","reset","taratur","calibr","lubrific","grasso","olio","manutenz",
            "vite","bullon","dado","rondell","filetto","coppia","torque","diametro","tolleranz","acciaio","inox","allumin","bronzo",
            "cavo","tensione","corrente","volt","ampere","ohm","resistenz","condensator","trasformator","alimentator","circuit",
            "contatto","morsetto","pressostat","termostat","guarnizion","oring","o ring","tenuta","mig","tig","elettrodo",
            "2b60","sas2","trafila","forno"
    };

    public static boolean isSocial(String normalized) {
        String x = normalized == null ? "" : normalized.trim();
        for (String s : SOCIAL) if (x.equals(s) || x.startsWith(s + " ") || x.contains(" " + s + " ")) return true;
        return false;
    }

    public static boolean isClearlyOffTopic(String normalized) {
        String x = normalized == null ? "" : normalized;
        for (String s : OFF_TOPIC) if (x.contains(s)) return true;
        return false;
    }

    public static boolean isTechnical(String normalized) {
        String x = normalized == null ? "" : normalized;
        for (String s : TECH) if (x.contains(s)) return true;
        return false;
    }

    public static String socialReply(String normalized) {
        String x = normalized == null ? "" : normalized;
        if (x.contains("grazie")) return "Figurati. Sono qui: dimmi pure cosa devi controllare o cercare.";
        if (x.contains("come stai") || x.contains("come va") || x.contains("tutto bene"))
            return "Tutto bene, pronto a darti una mano. Che succede oggi?";
        if (x.contains("buongiorno")) return "Buongiorno. Dimmi pure: guasto, turno, manuale o una domanda tecnica?";
        if (x.contains("buonasera")) return "Buonasera. Sono operativo: cosa ti serve?";
        return "Ciao. Dimmi pure cosa ti serve in manutenzione.";
    }

    public static String offlineAnswer(String normalized) {
        String x = normalized == null ? "" : normalized.toLowerCase(Locale.ITALY);

        if ((x.contains("elettrovalvol") || x.contains("valvol")) && x.contains("monostabil") && x.contains("bistabil")) {
            return "In breve: una elettrovalvola monostabile ha una posizione di riposo definita e, quando togli il comando, una molla la riporta lì. Una bistabile mantiene invece l'ultima posizione comandata finché non riceve il comando opposto. In manutenzione questo cambia molto il comportamento in mancanza di tensione: sulla monostabile sai dove torna, sulla bistabile devi considerare che può restare nell'ultima posizione. Prima di sostituirla controlla simbolo pneumatico/idraulico, numero di bobine, tensione bobine e funzione richiesta dalla macchina.";
        }

        if ((x.contains("rele") || x.contains("relè")) && x.contains("contattore")) {
            return "Relè e contattore fanno entrambi commutazione elettrica, ma il contattore è progettato per potenze e correnti maggiori, con contatti e camere di spegnimento adatti a carichi come motori. Il relè si usa più spesso nei circuiti di comando o per segnali. Per scegliere guarda corrente nominale, categoria d'impiego, tensione bobina e numero/tipo di contatti.";
        }

        if ((x.contains("pnp") && x.contains("npn")) || (x.contains("sensore") && x.contains("pnp"))) {
            return "Su un sensore PNP l'uscita, quando attiva, porta il positivo verso l'ingresso; su un NPN porta invece l'ingresso verso lo zero volt. Quindi sensore e scheda ingresso devono essere compatibili con il tipo di cablaggio. Prima di collegare verifica sempre schema del sensore, tensione di alimentazione e comune degli ingressi PLC.";
        }

        if ((x.contains("4 20") || x.contains("4-20") || x.contains("4 20ma")) && (x.contains("0 10") || x.contains("0-10"))) {
            return "Il 4–20 mA è un segnale in corrente ed è molto robusto su distanze lunghe e ambienti industriali; inoltre il valore 4 mA permette di distinguere lo zero di misura da un filo interrotto. Lo 0–10 V è semplice ma più sensibile a cadute di tensione e disturbi. La scelta dipende da trasmettitore, ingresso analogico e lunghezza/cablaggio del segnale.";
        }

        if (x.contains("accumulator") && (x.contains("idraulic") || x.contains("pression") || x.contains("cesoia"))) {
            return "Un accumulatore idraulico immagazzina energia in pressione e può fornire rapidamente portata nei picchi, smorzare pulsazioni o mantenere pressione. Se una cesoia perde forza durante il colpo, tra i controlli possibili ci sono pressione di precarica, tenuta dell'accumulatore, valvole, pompa e pressione reale durante il ciclo. La verifica della precarica va fatta solo con procedura prevista dal costruttore e impianto messo in sicurezza.";
        }

        if (x.contains("maniglia") && (x.contains("copertura") || x.contains("carter") || x.contains("cesoia"))) {
            return "Per una maniglia su una copertura partirei da quattro dati: materiale e spessore del carter, peso della copertura, spazio disponibile con macchina in movimento e tipo di fissaggio consentito. In genere è meglio una presa arrotondata senza spigoli, abbastanza distante dalla lamiera per i guanti, con due punti di fissaggio ben distanziati. Se puoi saldare, una staffa piegata o tondo sagomato è semplice; se la copertura va smontata spesso, una maniglia imbullonata e sostituibile è più pratica. Prima di definire quote e materiale dimmi spessore della lamiera, peso indicativo e se puoi saldare o devi imbullonare.";
        }

        if ((x.contains("radiocomando") || x.contains("radio comando")) && (x.contains("accopp") || x.contains("abbina") || x.contains("pair") || x.contains("gru") || x.contains("carroponte"))) {
            return "Per accoppiare un radiocomando a una gru non userei una procedura generica: cambia molto tra costruttori e modelli e può coinvolgere funzioni di sicurezza. Mi serve marca e modello esatti di trasmettitore e ricevitore, oppure una foto della targhetta. Prima cerco il manuale corretto e poi ti indico la sezione di pairing, senza inventare passaggi.";
        }

        if (x.contains("inverter") && (x.contains("manuale") || x.contains("parametr") || x.contains("errore") || x.contains("allarme"))) {
            return "Posso aiutarti, ma per essere preciso mi serve marca e modello esatto dell'inverter e, se c'è, il codice allarme. Se il manuale è già caricato in ValMan lo cerco lì; altrimenti posso preparare la ricerca del manuale ufficiale del costruttore.";
        }

        if (x.contains("manuale") || x.contains("datasheet") || x.contains("schema")) {
            return "Cerco prima nei documenti ValMan. Se non c'è, indicami marca e modello completi: per i manuali tecnici è meglio usare la documentazione ufficiale del costruttore anziché una procedura generica.";
        }

        return "";
    }
}
