
/*  $Id: AbstractPinTanPassport.java,v 1.6 2011/06/06 10:30:31 willuhn Exp $

    This file is part of HBCI4Java
    Copyright (C) 2001-2008  Stefan Palme

    HBCI4Java is free software; you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation; either version 2 of the License, or
    (at your option) any later version.

    HBCI4Java is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program; if not, write to the Free Software
    Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package org.kapott.hbci.passport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

import org.kapott.hbci.GV.GVTAN2Step;
import org.kapott.hbci.GV.HBCIJobImpl;
import org.kapott.hbci.callback.HBCICallback;
import org.kapott.hbci.comm.Comm;
import org.kapott.hbci.dialog.DialogContext;
import org.kapott.hbci.dialog.DialogEvent;
import org.kapott.hbci.dialog.HBCIMessage;
import org.kapott.hbci.dialog.HBCIMessageQueue;
import org.kapott.hbci.dialog.KnownDialogTemplate;
import org.kapott.hbci.dialog.KnownReturncode;
import org.kapott.hbci.dialog.KnownTANProcess;
import org.kapott.hbci.dialog.RawHBCIDialog;
import org.kapott.hbci.exceptions.HBCI_Exception;
import org.kapott.hbci.exceptions.InvalidUserDataException;
import org.kapott.hbci.manager.ChallengeInfo;
import org.kapott.hbci.manager.HBCIDialog;
import org.kapott.hbci.manager.HBCIHandler;
import org.kapott.hbci.manager.HBCIKernelImpl;
import org.kapott.hbci.manager.HBCIKey;
import org.kapott.hbci.manager.HBCIUser;
import org.kapott.hbci.manager.HBCIUtils;
import org.kapott.hbci.manager.HBCIUtilsInternal;
import org.kapott.hbci.manager.TanMethod;
import org.kapott.hbci.protocol.SEG;
import org.kapott.hbci.protocol.factory.SEGFactory;
import org.kapott.hbci.security.Crypt;
import org.kapott.hbci.security.Sig;
import org.kapott.hbci.status.HBCIMsgStatus;
import org.kapott.hbci.status.HBCIRetVal;
import org.kapott.hbci.structures.Konto;
import org.kapott.hbci.tools.DigestUtils;
import org.kapott.hbci.tools.NumberUtil;
import org.kapott.hbci.tools.ParameterFinder;
import org.kapott.hbci.tools.ParameterFinder.Query;
import org.kapott.hbci.tools.StringUtil;

public abstract class AbstractPinTanPassport extends AbstractHBCIPassport
{
    /**
     * Hier speichern wir zwischen, ob wir eine HKTAN-Anfrage in der Dialog-Initialisierung gesendet haben und wenn ja, welcher Prozess-Schritt es war
     */
    private final static String CACHE_KEY_SCA_STEP = "__sca_step__";
    
    /**
     * Hier speichern wir, ob wir eine SCA-Ausnahme fuer einen GV von der Bank erhalten haben
     */
    public final static String KEY_PD_SCA = "__pintan_sca___";

    /**
     * Hier speichern wir den Challenge-Text der Bank fuer die TAN-Abfrage.
     */
    public final static String KEY_PD_CHALLENGE = "__pintan_challenge___";

    /**
     * Hier speichern wir das HHDuc fuer die TAN-Abfrage.
     */
    public final static String KEY_PD_HHDUC = "__pintan_hhduc___";

    /**
     * Hier speichern wir die Auftragsreferenz fuer die TAN-Abfrage.
     */
    public final static String KEY_PD_ORDERREF = "__pintan_orderref___";

    private String    certfile;
    private boolean   checkCert;

    private String    proxy;
    private String    proxyuser;
    private String    proxypass;

    private boolean   verifyTANMode;
    
    private String    currentTANMethod;
    private boolean   currentTANMethodWasAutoSelected;
    
    // Die IDs aller erlaubten TAN-Verfahren
    private List<String> allowedTANMethods;

    // Die Map mit den BPDs zu den TAN-Verfahren
    private Hashtable<String,Properties> twostepMechanisms;

    private String    pin;
    
    /**
     * ct.
     * @param initObject
     */
    public AbstractPinTanPassport(Object initObject)
    {
        super(initObject);
        this.twostepMechanisms=new Hashtable<String, Properties>();
        this.allowedTANMethods=new ArrayList<String>();
    }
    
    /**
     * @see org.kapott.hbci.passport.HBCIPassportInternal#getPassportTypeName()
     */
    public String getPassportTypeName()
    {
        return "PinTan";
    }

    /**
     * @see org.kapott.hbci.passport.AbstractHBCIPassport#setBPD(java.util.Properties)
     */
    public void setBPD(Properties p)
    {
        super.setBPD(p);

        if (p!=null && p.size()!=0) {
            // hier die liste der verfügbaren sicherheitsverfahren aus den
            // bpd (HITANS) extrahieren

            twostepMechanisms.clear();
            
            // willuhn 2011-06-06 Maximal zulaessige HITANS-Segment-Version ermitteln
            // Hintergrund: Es gibt User, die nur HHD 1.3-taugliche TAN-Generatoren haben,
            // deren Banken aber auch HHD 1.4 beherrschen. In dem Fall wuerde die Bank
            // HITANS/HKTAN/HITAN in Segment-Version 5 machen, was in der Regel dazu fuehren
            // wird, dass HHD 1.4 zur Anwendung kommt. Das bewirkt, dass ein Flicker-Code
            // erzeugt wird, der vom TAN-Generator des Users gar nicht lesbar ist, da dieser
            // kein HHD 1.4 beherrscht. Mit dem folgenden Parameter kann die Maximal-Version
            // des HITANS-Segments nach oben begrenzt werden, so dass z.Bsp. HITANS5 ausgefiltert
            // wird.
            int maxAllowedVersion = Integer.parseInt(HBCIUtils.getParam("kernel.gv.HITANS.segversion.max","0"));

            for (Enumeration e=p.propertyNames();e.hasMoreElements();) {
                String key=(String)e.nextElement();

                // p.getProperty("Params_x.TAN2StepParY.ParTAN2StepZ.TAN2StepParamsX_z.*")
                if (key.startsWith("Params")) {
                    String subkey=key.substring(key.indexOf('.')+1);
                    if (subkey.startsWith("TAN2StepPar")) {
                      
                        // willuhn 2011-05-13 Wir brauchen die Segment-Version, weil mittlerweile TAN-Verfahren
                        // mit identischer Sicherheitsfunktion in unterschiedlichen Segment-Versionen auftreten koennen
                        // Wenn welche mehrfach vorhanden sind, nehmen wir nur das aus der neueren Version
                        int segVersion = Integer.parseInt(subkey.substring(11,12));
                        
                        subkey=subkey.substring(subkey.indexOf('.')+1);
                        if (subkey.startsWith("ParTAN2Step") &&
                                subkey.endsWith(".secfunc"))
                        {
                            // willuhn 2011-06-06 Segment-Versionen ueberspringen, die groesser als die max. zulaessige sind
                            if (maxAllowedVersion > 0 && segVersion > maxAllowedVersion)
                            {
                              HBCIUtils.log("skipping segversion " + segVersion + ", larger than allowed version " + maxAllowedVersion, HBCIUtils.LOG_DEBUG);
                              continue;
                            }

                            String secfunc=p.getProperty(key);

                            // willuhn 2011-05-13 Checken, ob wir das Verfahren schon aus einer aktuelleren Segment-Version haben
                            Properties prev = twostepMechanisms.get(secfunc);
                            if (prev != null)
                            {
                              // Wir haben es schonmal. Mal sehen, welche Versionsnummer es hat
                              int prevVersion = Integer.parseInt(prev.getProperty("segversion"));
                              if (prevVersion > segVersion)
                              {
                                HBCIUtils.log("found another twostepmech " + secfunc + " in segversion " + segVersion + ", already have one in segversion " + prevVersion + ", ignoring segversion " + segVersion, HBCIUtils.LOG_DEBUG);
                                continue;
                              }
                            }
                            
                            Properties entry=new Properties();
                            
                            // willuhn 2011-05-13 Wir merken uns die Segment-Version in dem Zweischritt-Verfahren
                            // Daran koennen wir erkennen, ob wir ein mehrfach auftretendes
                            // Verfahren ueberschreiben koennen oder nicht.
                            entry.put("segversion",Integer.toString(segVersion));

                            String     paramHeader=key.substring(0,key.lastIndexOf('.'));
                            // Params_x.TAN2StepParY.ParTAN2StepZ.TAN2StepParamsX_z

                            // alle properties durchlaufen und alle suchen, die mit dem
                            // paramheader beginnen, und die entsprechenden werte im
                            // entry abspeichern
                            for (Enumeration e2=p.propertyNames();e2.hasMoreElements();) {
                                String key2=(String)e2.nextElement();

                                if (key2.startsWith(paramHeader+".")) {
                                    int dotPos=key2.lastIndexOf('.');

                                    entry.setProperty(
                                            key2.substring(dotPos+1),
                                            p.getProperty(key2));
                                }
                            }

                            // diesen mechanismus abspeichern
                            twostepMechanisms.put(secfunc,entry);
                        }
                    }
                }
            }
        }
    }

    /**
     * @see org.kapott.hbci.passport.AbstractHBCIPassport#onDialogEvent(org.kapott.hbci.dialog.DialogEvent, org.kapott.hbci.dialog.DialogContext)
     */
    @Override
    public void onDialogEvent(DialogEvent event, DialogContext ctx)
    {
        super.onDialogEvent(event, ctx);

        if (event == DialogEvent.MSG_CREATED)
        {
            this.checkSCARequest(ctx);
        }
        else if (event == DialogEvent.MSG_SENT)
        {
            this.checkInvalidPIN(ctx);
            this.check3920(ctx);
            this.check3072(ctx);
            this.checkSCAResponse(ctx);
        }
        else if (event == DialogEvent.JOBS_CREATED)
        {
            this.patchMessagesFor2StepMethods(ctx);
        }
    }
    
    /**
     * Prueft, ob es Anzeichen fuer eine falsche PIN gibt.
     * Wenn ja, geben wir per Callback Bescheid.
     * @param ctx der Kontext.
     */
    private void checkInvalidPIN(DialogContext ctx)
    {
        // Falsche PIN kann es bei einem anonymen Dialog nicht geben
        if (ctx.isAnonymous())
            return;

        HBCIMsgStatus status = ctx.getMsgStatus();
        if (status == null)
            return;
        
        if (status.isOK())
            return;
        
        HBCIRetVal ret = status.getInvalidPINCode();
        
        if (ret == null)
            return;

        HBCIUtils.log("PIN-Fehler erkannt, Meldung der Bank: " + ret.code + ": " + ret.text, HBCIUtils.LOG_INFO);
        this.clearPIN();
        
        // Aufrufer informieren, dass falsche PIN eingegeben wurde (um evtl. PIN aus Puffer zu löschen, etc.) 
        HBCIUtilsInternal.getCallback().callback(this,HBCICallback.WRONG_PIN,"*** invalid PIN entered",HBCICallback.TYPE_TEXT,new StringBuffer());
    }
    
    /**
     * Prueft, ob im Response der Code 3920 enthalten ist.
     * Dort liefert die Bank neue Zweischritt-Verfahren.
     * @param ctx der Kontext.
     */
    private void check3920(DialogContext ctx)
    {
        HBCIMsgStatus status = ctx.getMsgStatus();
        if (status == null)
            return;

        ////////////////////////////////////////////////////
        // TAN-Verfahren ermitteln und uebernehmen
        HBCIRetVal[] glob = status.globStatus != null ? status.globStatus.getWarnings() : null;
        HBCIRetVal[] seg  = status.segStatus != null ? status.segStatus.getWarnings() : null;

        if (glob == null && seg == null)
            return;

        HBCIUtils.log("autosecfunc: search for 3920 in response to detect allowed twostep secmechs", HBCIUtils.LOG_DEBUG);

        List<HBCIRetVal> globRet = KnownReturncode.W3920.searchReturnValues(glob);
        List<HBCIRetVal> segRet  = KnownReturncode.W3920.searchReturnValues(seg);

        if (globRet == null && segRet == null)
            return;
        
        final List<String> oldList = new ArrayList<String>(this.allowedTANMethods);
        final Set<String> newSet = new HashSet<String>(); // Damit doppelte nicht doppelt in der Liste landen
        
        if (globRet != null)
        {
            for (HBCIRetVal r:globRet)
            {
                if (r.params != null)
                    newSet.addAll(Arrays.asList(r.params));
            }
        }
        if (segRet != null)
        {
            for (HBCIRetVal r:segRet)
            {
                if (r.params != null)
                    newSet.addAll(Arrays.asList(r.params));
            }
        }
        
        final List<String> newList = new ArrayList<String>(newSet);

        if (newList.size() > 0 && !newList.equals(oldList))
        {
            this.allowedTANMethods.clear();
            this.allowedTANMethods.addAll(newList);
            HBCIUtils.log("autosecfunc: found 3920 in response - updated list of allowed twostepmechs - old: " + oldList + ", new: " + this.allowedTANMethods, HBCIUtils.LOG_DEBUG);
        }
        //
        ////////////////////////////////////////////////////
        
        if (this.isAnonymous())
            return;

        ////////////////////////////////////////////////////
        // Dialog neu starten, wenn das Verfahren sich geaendert hat
        // aktuelle secmech merken und neue auswählen (basierend auf evtl. gerade neu empfangenen informationen (3920s))
        final String oldMethod = this.currentTANMethod;
        final String newMethod = this.getCurrentTANMethod(true);
        
        if (Objects.equals(oldMethod,newMethod))
            return;

        // Wenn es eine Synchronisierung ist, lassen wir das Repeat weg.
        // Die Postbank kommt nicht damit klar, wenn man eine neue Synchronisierung mit dem anderen TAN-Verfahren direkt hinterher sendet
        if (ctx.getDialogInit().getTemplate() == KnownDialogTemplate.SYNC)
            return;

        // wenn sich das ausgewählte secmech geändert hat, müssen wir
        // einen dialog-restart fordern, weil während eines dialoges
        // das secmech nicht gewechselt werden darf
        HBCIUtils.log("autosecfunc: after this dialog-init we had to change selected pintan method from " + oldMethod + " to " + newMethod + ", so a restart of this dialog is needed", HBCIUtils.LOG_DEBUG);
        HBCIUtils.log("Derzeitiges TAN-Verfahren aktualisiert, starte Dialog neu", HBCIUtils.LOG_INFO);
        
        ctx.setRepeat(true);
        //ctx.setDialogEnd(true);
        //
        ////////////////////////////////////////////////////

    }

    /**
     * Prueft, ob im Response der Code 3072 enthalten ist.
     * Dort liefert die Bank ggf. aktualisierte Zugangsdaten.
     * @param ctx der Kontext.
     */
    private void check3072(DialogContext ctx)
    {
        // Neue Zugangsdaten kann es anonym nicht geben.
        if (ctx.isAnonymous())
            return;

        HBCIMsgStatus status = ctx.getMsgStatus();
        if (status == null)
            return;

        HBCIRetVal[] seg = status.segStatus != null ? status.segStatus.getWarnings() : null;
        if (seg == null)
            return;
        
        final HBCIRetVal ret = KnownReturncode.W3072.searchReturnValue(seg);
        if (ret == null)
            return;
        
        String newCustomerId = "";
        String newUserId = "";
        int l2=ret.params.length;
        if(l2>0) {
            newUserId = ret.params[0];
            newCustomerId = ret.params[0];
        }
        if(l2>1) {
            newCustomerId = ret.params[1];
        }
        if(l2>0) {
            HBCIUtils.log("autosecfunc: found 3072 in response - change user id", HBCIUtils.LOG_DEBUG);
            // Aufrufer informieren, dass UserID und CustomerID geändert wurde
            StringBuffer retData=new StringBuffer();
            retData.append(newUserId+"|"+newCustomerId);
            HBCIUtilsInternal.getCallback().callback(this,HBCICallback.USERID_CHANGED,"*** User ID changed",HBCICallback.TYPE_TEXT,retData);
        }
    }
    
    /**
     * Prueft, ob der Request um ein HKTAN erweitert werden muss.
     * @param ctx der Kontext.
     */
    private  void checkSCARequest(DialogContext ctx)
    {
        final RawHBCIDialog init = ctx.getDialogInit();
        if (init == null)
            return;

        // Checken, ob es ein Dialog, in dem eine SCA gemacht werden soll
        if (!KnownDialogTemplate.LIST_SEND_SCA.contains(init.getTemplate()))
            return;

        final int segversionDefault = 6;
        final String processDefault = "2";
        
        /////////////////////////////////////////////////////////////////////////
        // HKTAN-Version und Prozessvariante ermitteln - kann NULL sein
        final Properties secmechInfo = this.getCurrentSecMechInfo();
        
//        // Wir haben keine TAN-Verfahren - dann koennen wir eh noch nichts ermitteln
//        if (secmechInfo == null || secmechInfo.size() == 0)
//            return;

        final int hktanVersion = secmechInfo != null ? NumberUtil.parseInt(secmechInfo.getProperty("segversion"),segversionDefault) : segversionDefault;
        
        // Erst ab HKTAN 6 noetig. Die Bank unterstuetzt es scheinbar noch nicht
        // Siehe B.4.3.1 - Wenn die Bank HITAN < 6 geschickt hat, dann kann sie keine SCA
        if (hktanVersion < 6)
            return;

        final String process = secmechInfo != null ? secmechInfo.getProperty("process",processDefault) : processDefault; // Prozessvariante (meist 2, gibt es ueberhaupt noch jemand mit 1?)
        final boolean isP2 = process != null && process.equals("2");
        //
        /////////////////////////////////////////////////////////////////////////
        
        Integer scaStep = (Integer) ctx.getMeta().get(CACHE_KEY_SCA_STEP);
        // Wir haben noch kein HKTAN gesendet. Dann senden wir jetzt Schritt 1
        if (scaStep == null)
            scaStep = 1;

        ctx.getMeta().put(CACHE_KEY_SCA_STEP,scaStep);

        // Prozess-Variante 1 ist die mit den Schabloben und Challenge-Klassen
        final boolean step2 = scaStep.intValue() == 2;
        final KnownTANProcess tp = isP2 ? (step2 ? KnownTANProcess.PROCESS2_STEP2 : KnownTANProcess.PROCESS2_STEP1) : KnownTANProcess.PROCESS1;
        
        final HBCIKernelImpl k = ctx.getKernel();
        
        final String prefix = "TAN2Step" + hktanVersion;
        
        // wir fuegen die Daten des HKTAN ein
        k.rawSet(prefix,"requested"); // forcieren, dass das Segment mit gesendet wird - auch wenn es eigentlich optional ist
        k.rawSet(prefix + ".process",tp.getCode());
        
        // Beim Bezug auf das Segment schicken wir per Default "HKIDN". Gemaess Kapitel B.4.3.1 muss das Bezugssegment aber
        // bei PIN/TAN-Management-Geschaeftsvorfaellen mit dem GV des jeweiligen Geschaeftsvorfalls belegt werden.
        // Daher muessen wir im Payload schauen, ob ein entsprechender Geschaeftsvorfall enthalten ist.
        // Wird muessen nur nach HKPAE, HKTAB schauen - das sind die einzigen beiden, die wir unterstuetzen
        String segcode = "HKIDN";
        HBCIDialog payload = ctx.getDialog();
        if (payload != null)
        {
            final HBCIMessageQueue queue = payload.getMessageQueue();
            for (String code:Arrays.asList("HKPAE","HKTAB")) // Das sind GVChangePIN und GVTANMediaList
            {
                if (queue.findTask(code) != null)
                {
                    segcode = code;
                    break;
                }
            }
        }
        
        HBCIUtils.log("creating " + (step2 ? "2nd" : "1st") + " HKTAN for SCA [process variant: " + process + ", process number: " + tp.getCode() + ", order code: " + segcode + "]",HBCIUtils.LOG_DEBUG);
        
        k.rawSet(prefix + ".ordersegcode",segcode);
        k.rawSet(prefix + ".OrderAccount.bic","");
        k.rawSet(prefix + ".OrderAccount.iban","");
        k.rawSet(prefix + ".OrderAccount.number","");
        k.rawSet(prefix + ".OrderAccount.subnumber","");
        k.rawSet(prefix + ".OrderAccount.KIK.blz","");
        k.rawSet(prefix + ".OrderAccount.KIK.country","");
        k.rawSet(prefix + ".orderhash",isP2 ? "" : ("B00000000"));
        k.rawSet(prefix + ".orderref",step2 ? (String) this.getPersistentData(KEY_PD_ORDERREF) : "");
        k.rawSet(prefix + ".notlasttan","N");
        k.rawSet(prefix + ".challengeklass",isP2 ? "" : "99");
        k.rawSet(prefix + ".tanmedia",this.getTanMedia(hktanVersion));
    }

    /**
     * Prueft das Response auf Vorhandensein eines HITAN bzw Code.
     * Hinweis: Wir haben das ganze HKTAN-Handling derzeit leider doppelt. Einmal fuer die Dialog-Initialisierung und einmal fuer
     * die Nachrichten mit den eigentlichen Geschaeftsvorfaellen (in patchMessagesFor2StepMethods). Wenn auch HBCIDialog#doJobs irgendwann
     * auf die neuen RawHBCIDialoge umgestellt ist, kann eigentlich patchMessagesFor2StepMethods entfallen.
     * @param ctx der Kontext.
     */
    private void checkSCAResponse(DialogContext ctx)
    {
        final RawHBCIDialog init = ctx.getDialogInit();
        if (init == null)
            return;
        
        // Checken, ob es ein Dialog, in dem eine SCA gemacht werden soll
        if (!KnownDialogTemplate.LIST_SEND_SCA.contains(init.getTemplate()))
            return;

        // Wenn wir noch in der anonymen Dialog-Initialisierung sind, interessiert uns das nicht.
        if (ctx.isAnonymous() || this.isAnonymous())
        {
            HBCIUtils.log("anonymous dialog, skip SCA response analysis",HBCIUtils.LOG_DEBUG);
            ctx.getMeta().remove(CACHE_KEY_SCA_STEP);
            return;
        }

        Integer scaStep = (Integer) ctx.getMeta().get(CACHE_KEY_SCA_STEP);
        
        // Wenn wir keinen SCA-Request gesendet haben, brauchen wir auch nicht nach dem Response suchen
        if (scaStep == null)
            return;

        // Ohne Status brauchen wir es gar nicht erst versuchen
        final HBCIMsgStatus status = ctx.getMsgStatus();
        if (status == null)
            return;

        // Bank hat uns eine Ausnahme erteilt - wir brauchen keine TAN
        if (status.segStatus != null && (KnownReturncode.W3076.searchReturnValue(status.segStatus.getWarnings()) != null || KnownReturncode.W3076.searchReturnValue(status.globStatus.getWarnings()) != null))
        {
            HBCIUtils.log("found status code 3076, no SCA required",HBCIUtils.LOG_DEBUG);
            ctx.getMeta().remove(CACHE_KEY_SCA_STEP);
            return;
        }
        
        // Schritt 1: Wir haben eine HKTAN-Anfrage gesendet. Mal schauen, ob die Bank tatsaechlich eine TAN will
        if (scaStep.intValue() == 1)
        {
            HBCIUtils.log("HKTAN step 1 for SCA sent, checking for HITAN response [step: " + scaStep + "]",HBCIUtils.LOG_DEBUG);

            Properties props = ParameterFinder.find(status.getData(),"TAN2StepRes*.");
            if (props == null || props.size() == 0)
                return; // Wir haben kein HITAN

            // HITAN erhalten - Daten uebernehmen
            HBCIUtils.log("SCA HITAN response found, triggering TAN request",HBCIUtils.LOG_DEBUG);
            final String challenge = props.getProperty("challenge");
            if (challenge != null && challenge.length() > 0)
                this.setPersistentData(KEY_PD_CHALLENGE,challenge);
            
            final String hhdUc = props.getProperty("challenge_hhd_uc");
            if (hhdUc != null && hhdUc.length() > 0)
                this.setPersistentData(KEY_PD_HHDUC,hhdUc);
            
            final String orderref = props.getProperty("orderref");
            if (orderref != null && orderref.length() > 0)
                this.setPersistentData(KEY_PD_ORDERREF,orderref);

            /////////////////////////////////////////////////////
            // Dialog-Init wiederholen, um den zweiten HKTAN-Schritt durchzufuehren
            // OK, wir senden jetzt das finale HKTAN. Die Message darf nichts anderes enthalten. Daher aendern wir das Template.
            ctx.getMeta().put(CACHE_KEY_SCA_STEP,2);
            ctx.getDialogInit().setTemplate(KnownDialogTemplate.INIT_SCA);
            ctx.setRepeat(true);
            //
            /////////////////////////////////////////////////////
            
            return;
        }
        
        if (scaStep.intValue() == 2)
        {
            ctx.getMeta().remove(CACHE_KEY_SCA_STEP); // Geschafft
            HBCIUtils.log("HKTAN step 2 for SCA sent, checking for HITAN response [step: " + scaStep + "]",HBCIUtils.LOG_DEBUG);
            Properties props = ParameterFinder.find(status.getData(),"TAN2StepRes*.");
            if (props.size() > 0)
                HBCIUtils.log("final SCA HITAN response found",HBCIUtils.LOG_DEBUG);
        }
    }
    
    /**
     * @see org.kapott.hbci.passport.AbstractHBCIPassport#getCommInstance()
     */
    public Comm getCommInstance()
    {
        return Comm.getInstance("PinTan",this);
    }
    
    /**
     * @see org.kapott.hbci.passport.HBCIPassport#isSupported()
     */
    public boolean isSupported()
    {
        boolean ret=false;
        Properties bpd=getBPD();
        
        if (bpd!=null && bpd.size()!=0) {
            // loop through bpd and search for PinTanPar segment
            for (Enumeration e=bpd.propertyNames();e.hasMoreElements();) {
                String key=(String)e.nextElement();
                
                if (key.startsWith("Params")) {
                    int posi=key.indexOf(".");
                    if (key.substring(posi+1).startsWith("PinTanPar")) {
                        ret=true;
                        break;
                    }
                }
            }
            
            if (ret) {
                // prüfen, ob gewähltes sicherheitsverfahren unterstützt wird
                // autosecmech: hier wird ein flag uebergeben, das anzeigt, dass getCurrentTANMethod()
                // hier evtl. automatisch ermittelte secmechs neu verifzieren soll
                String current=getCurrentTANMethod(true);
                
                if (current.equals(TanMethod.ONESTEP.getId())) {
                    // einschrittverfahren gewählt
                    if (!isOneStepAllowed()) {
                        HBCIUtils.log("not supported: onestep method not allowed by BPD",HBCIUtils.LOG_ERR);
                        ret=false;
                    } else {
                        HBCIUtils.log("supported: pintan-onestep",HBCIUtils.LOG_DEBUG);
                    }
                } else {
                    // irgendein zweischritt-verfahren gewählt
                    Properties entry=twostepMechanisms.get(current);
                    if (entry==null) {
                        // es gibt keinen info-eintrag für das gewählte verfahren
                        HBCIUtils.log("not supported: twostep-method "+current+" selected, but this is not supported",HBCIUtils.LOG_ERR);
                        ret=false;
                    } else {
                        HBCIUtils.log("selected twostep-method "+current+" ("+entry.getProperty("name")+") is supported",HBCIUtils.LOG_DEBUG);
                    }
                }
            }
        } else {
            ret=true;
        }
        
        return ret;
    }
    
    /**
     * Liefert true, wenn das TAN-Einschritt-Verfahren unterstuetzt wird.
     * @return true, wenn das TAN-Einschritt-Verfahren unterstuetzt wird.
     */
    private boolean isOneStepAllowed()
    {
      final Properties bpd = this.getBPD();
      if (bpd == null)
        return true;
      
      return ParameterFinder.findAll(bpd,ParameterFinder.Query.BPD_PINTAN_CAN1STEP).containsValue("J");
    }
    
    /** Kann vor <code>new HBCIHandler()</code> aufgerufen werden, um zu
     * erzwingen, dass die Liste der unterstützten PIN/TAN-Sicherheitsverfahren
     * neu vom Server abgeholt wird und evtl. neu vom Nutzer abgefragt wird. */
    public void resetSecMechs()
    {
        this.allowedTANMethods=new ArrayList<String>();
        this.currentTANMethod=null;
        this.currentTANMethodWasAutoSelected=false;
    }
    
    /**
     * Legt das aktuelle TAN-Verfahren fest.
     * @param method das aktuelle TAN-Verfahren.
     */
    public void setCurrentTANMethod(String method)
    {
        this.currentTANMethod=method;
    }
    
    /**
     * Liefert das aktuelle TAN-Verfahren.
     * @param recheck true, wenn die gespeicherte Auswahl auf Aktualitaet und Verfuegbarkeit geprueft werden soll.
     * Die Funktion kann in dem Fall einen Callback ausloesen, wenn mehrere Optionen zur Wahl stehen.
     * @return das TAN-Verfahren.
     */
    public String getCurrentTANMethod(boolean recheck)
    {
        // Wir haben ein aktuelles TAN-Verfahren und eine Neupruefung ist nicht noetig
        if (this.currentTANMethod != null && !recheck)
            return this.currentTANMethod;
        
        
        HBCIUtils.log("(re)checking selected pintan method", HBCIUtils.LOG_DEBUG);

        /////////////////////////////////////////
        // Die Liste der verfuegbaren Optionen ermitteln
        final List<TanMethod> options = new ArrayList<TanMethod>();
        final List<TanMethod> fallback = new ArrayList<TanMethod>();

        // Einschritt-Verfahren optional hinzufuegen
        if (this.isOneStepAllowed())
        {
            TanMethod m = TanMethod.ONESTEP;
            // Nur hinzufuegen, wenn wir entweder gar keine erlaubten haben oder es in der Liste der erlaubten drin ist
            if (this.allowedTANMethods.size() == 0 || this.allowedTANMethods.contains(m.getId()))
                options.add(m);
        }
        
        // Die Zweischritt-Verfahren hinzufuegen
        String[] secfuncs= this.twostepMechanisms.keySet().toArray(new String[this.twostepMechanisms.size()]);
        Arrays.sort(secfuncs);
        for (String secfunc:secfuncs)
        {
            final Properties entry = this.twostepMechanisms.get(secfunc);
            final TanMethod m = new TanMethod(secfunc,entry.getProperty("name"));
            if (this.allowedTANMethods.contains(secfunc))
            {
                options.add(m);
            }
            fallback.add(m);
        }
        //
        /////////////////////////////////////////

        
        /////////////////////////////////////////
        // 0 Optionen verfuegbar
        
        // Wir haben keine Optionen gefunden
        if (options.size() == 0)
        {
            // wir lassen das hier mal noch auf true stehen, weil das bestimmt noch nicht final war. Schliesslich basierte die
            // Auswahl des Verfahrens nicht auf den fuer den User freigeschalteten Verfahren sondern nur den allgemein von der
            // Bank unterstuetzten
            this.currentTANMethodWasAutoSelected = true;
            
            HBCIUtils.log("autosecfunc: no information about allowed pintan methods available", HBCIUtils.LOG_INFO);
            // Wir haben keine TAN-Verfahren, die fuer den User per 3920 zugelassen sind.
            // Wir schauen mal, ob die Bank wenigstens welche in HIPINS gemeldet hat. Wenn ja, dann soll der
            // User eins von dort auswaehlen. Ob das dann aber ein erlaubtes ist, wissen wir nicht.
            if (fallback.size() > 0)
            {
                HBCIUtils.log("autosecfunc: have some pintan methods in HIPINS, asking user, what to use from: " + fallback, HBCIUtils.LOG_INFO);
                final String selected = this.chooseTANMethod(fallback);
                this.setCurrentTANMethod(selected);
                HBCIUtils.log("autosecfunc: manually selected pintan method from HIPINS " + currentTANMethod, HBCIUtils.LOG_DEBUG);
            }
            else
            {
                TanMethod m = TanMethod.ONESTEP;
                HBCIUtils.log("autosecfunc: absolutly no information about allowed pintan methods available, fallback to " + m, HBCIUtils.LOG_WARN);
                this.setCurrentTANMethod(m.getId());
            }
            
            return this.currentTANMethod;
        }
        //
        /////////////////////////////////////////
        
        
        /////////////////////////////////////////
        // 1 Option verfuegbar
        if (options.size() == 1)
        {
            final TanMethod m = options.get(0);
            
            HBCIUtils.log("autosecfunc: there is only one pintan method supported - choosing this automatically: " + m,HBCIUtils.LOG_DEBUG);
            
            if (this.currentTANMethod != null && !this.currentTANMethod.equals(m.getId()))
                HBCIUtils.log("autosecfunc: auto-selected method differs from current: " + this.currentTANMethod, HBCIUtils.LOG_DEBUG);
            
            this.setCurrentTANMethod(m.getId());
            this.currentTANMethodWasAutoSelected = true;
            
            return this.currentTANMethod;
        }
        //
        /////////////////////////////////////////


        /////////////////////////////////////////
        // Mehrere Optionen verfuegbar

        // Checken, was gerade eingestellt ist.
        if (this.currentTANMethod != null)
        {
            boolean found = false;
            for (TanMethod m:options)
            {
                found |= this.currentTANMethod.equals(m.getId());
                if (found)
                    break;
            }
            
            if (!found)
            {
                HBCIUtils.log("autosecfunc: currently selected pintan method ("+this.currentTANMethod+") not in list of supported methods  " + options + " - resetting current selection", HBCIUtils.LOG_DEBUG);
                this.currentTANMethod = null;
            }
        }
        //
        /////////////////////////////////////////

        // Wenn wir jetzt immer noch ein Verfahren haben und dieses nicht automatisch gewaehlt wurde, dann
        // duerfen wir es verwenden.
        if (this.currentTANMethod != null && !this.currentTANMethodWasAutoSelected)
            return this.currentTANMethod;
        
        // User fragen
        HBCIUtils.log("autosecfunc: asking user what tan method to use. available methods: " + options,HBCIUtils.LOG_DEBUG);
        final String selected = this.chooseTANMethod(options);
          
        this.setCurrentTANMethod(selected);
        this.currentTANMethodWasAutoSelected = false;
        HBCIUtils.log("autosecfunc: manually selected pintan method "+currentTANMethod, HBCIUtils.LOG_DEBUG);
        return currentTANMethod;
    }
    
    /**
     * Fuehrt den Callback zur Auswahl des TAN-Verfahrens durch.
     * @param options die verfuegbaren Optionen.
     * @return das gewaehlte TAN-Verfahren.
     */
    private String chooseTANMethod(List<TanMethod> options)
    {
        final StringBuffer retData = new StringBuffer();
        for (TanMethod entry:options)
        {
            if (retData.length()!=0)
                retData.append("|");
            
            retData.append(entry.getId()).append(":").append(entry.getName());
        }
        
        HBCIUtilsInternal.getCallback().callback(this,HBCICallback.NEED_PT_SECMECH,"*** Select a pintan method from the list",HBCICallback.TYPE_TEXT,retData);
        
        // Pruefen, ob das gewaehlte Verfahren einem aus der Liste entspricht
        final String selected = retData.toString();
        
        for (TanMethod entry:options)
        {
            if  (selected.equals(entry.getId()))
                return selected;
        }
        
        throw new InvalidUserDataException("*** selected pintan method not supported: " + selected);
    }
    
    public Properties getCurrentSecMechInfo()
    {
        return twostepMechanisms.get(getCurrentTANMethod(false));
    }
    
    public Hashtable<String, Properties> getTwostepMechanisms()
    {
    	return twostepMechanisms;
    }

    public String getProfileMethod()
    {
        return "PIN";
    }
    
    public String getProfileVersion()
    {
        return getCurrentTANMethod(false).equals(TanMethod.ONESTEP.getId())?"1":"2";
    }

    public boolean needUserKeys()
    {
        return false;
    }
    
    public boolean needInstKeys()
    {
        // TODO: das abhängig vom thema "bankensignatur für HKTAN" machen
        return false;
    }
    
    public boolean needUserSig()
    {
        return true;
    }
    
    public String getSysStatus()
    {
        return "1";
    }

    public boolean hasInstSigKey()
    {
        // TODO: hier müsste es eigentlich zwei antworten geben: eine für
        // das PIN/TAN-verfahren an sich (immer true) und eine für
        // evtl. bankensignatur-schlüssel für HITAN
        return true;
    }
    
    public boolean hasInstEncKey()
    {
        return true;
    }
    
    public boolean hasMySigKey()
    {
        return true;
    }
    
    public boolean hasMyEncKey()
    {
        return true;
    }
    
    public HBCIKey getInstSigKey()
    {
        // TODO: hier müsste es eigentlich zwei antworten geben: eine für
        // das PIN/TAN-verfahren an sich (immer null) und eine für
        // evtl. bankensignatur-schlüssel für HITAN
        return null;
    }
    
    public HBCIKey getInstEncKey()
    {
        return null;
    }
    
    public String getInstSigKeyName()
    {
        // TODO: evtl. zwei antworten: pin/tan und bankensignatur für HITAN
        return getUserId();
    }

    public String getInstSigKeyNum()
    {
        // TODO: evtl. zwei antworten: pin/tan und bankensignatur für HITAN
        return "0";
    }

    public String getInstSigKeyVersion()
    {
        // TODO: evtl. zwei antworten: pin/tan und bankensignatur für HITAN
        return "0";
    }

    public String getInstEncKeyName()
    {
        return getUserId();
    }

    public String getInstEncKeyNum()
    {
        return "0";
    }

    public String getInstEncKeyVersion()
    {
        return "0";
    }

    public String getMySigKeyName()
    {
        return getUserId();
    }

    public String getMySigKeyNum()
    {
        return "0";
    }

    public String getMySigKeyVersion()
    {
        return "0";
    }

    public String getMyEncKeyName()
    {
        return getUserId();
    }

    public String getMyEncKeyNum()
    {
        return "0";
    }

    public String getMyEncKeyVersion()
    {
        return "0";
    }
    
    public HBCIKey getMyPublicDigKey()
    {
        return null;
    }

    public HBCIKey getMyPrivateDigKey()
    {
        return null;
    }

    public HBCIKey getMyPublicSigKey()
    {
        return null;
    }

    public HBCIKey getMyPrivateSigKey()
    {
        return null;
    }

    public HBCIKey getMyPublicEncKey()
    {
        return null;
    }

    public HBCIKey getMyPrivateEncKey()
    {
        return null;
    }

    public String getCryptMode()
    {
        // dummy-wert
        return Crypt.ENCMODE_CBC;
    }

    public String getCryptAlg()
    {
        // dummy-wert
        return Crypt.ENCALG_2K3DES;
    }

    public String getCryptKeyType()
    {
        // dummy-wert
        return Crypt.ENC_KEYTYPE_DDV;
    }

    public String getSigFunction()
    {
        return getCurrentTANMethod(false);
    }

    public String getCryptFunction()
    {
        return Crypt.SECFUNC_ENC_PLAIN;
    }

    public String getSigAlg()
    {
        // dummy-wert
        return Sig.SIGALG_RSA;
    }

    public String getSigMode()
    {
        // dummy-wert
        return Sig.SIGMODE_ISO9796_1;
    }

    public String getHashAlg()
    {
        // dummy-wert
        return Sig.HASHALG_RIPEMD160;
    }
    
    public void setInstSigKey(HBCIKey key)
    {
    }

    public void setInstEncKey(HBCIKey key)
    {
        // TODO: implementieren für bankensignatur bei HITAN
    }

    public void setMyPublicDigKey(HBCIKey key)
    {
    }

    public void setMyPrivateDigKey(HBCIKey key)
    {
    }

    public void setMyPublicSigKey(HBCIKey key)
    {
    }

    public void setMyPrivateSigKey(HBCIKey key)
    {
    }

    public void setMyPublicEncKey(HBCIKey key)
    {
    }

    public void setMyPrivateEncKey(HBCIKey key)
    {
    }
    
    public void incSigId()
    {
        // for PinTan we always use the same sigid
    }

    protected String collectSegCodes(String msg)
    {
        StringBuffer ret=new StringBuffer();
        int          len=msg.length();
        int          posi=0;
        
        while (true) {
            int endPosi=msg.indexOf(':',posi);
            if (endPosi==-1) {
                break;
            }
            
            String segcode=msg.substring(posi,endPosi);
            if (ret.length()!=0) {
                ret.append("|");
            }
            ret.append(segcode);
            
            while (posi<len && msg.charAt(posi)!='\'') {
                posi=HBCIUtilsInternal.getPosiOfNextDelimiter(msg,posi+1);
            }
            if (posi>=len) {
                break;
            }
            posi++;
        }
        
        return ret.toString();
    }

    /**
     * Liefert "J" oder "N" aus den BPD des Geschaeftsvorfalls, ob fuer diesen eine TAN erforderlich ist.
     * @param code der GV-Code.
     * @return "J" oder "N". Oder "A", wenn es ein Admin-Segment ist, jedoch keine TAN noetig ist.
     */
    public String getPinTanInfo(String code)
    {
        String     ret="";
        Properties bpd = getBPD();
        
        if (bpd == null)
            return ret;
        
        boolean isGV = false;
        final String paramCode = StringUtil.toParameterCode(code);
        
        for (Enumeration e=bpd.propertyNames();e.hasMoreElements();) {
            String key=(String)e.nextElement();

            if (key.startsWith("Params")&&
                    key.substring(key.indexOf(".")+1).startsWith("PinTanPar") &&
                    key.indexOf(".ParPinTan.PinTanGV")!=-1 &&
                    key.endsWith(".segcode")) 
            {
                String code2=bpd.getProperty(key);
                if (code.equals(code2)) {
                    key=key.substring(0,key.length()-("segcode").length())+"needtan";
                    ret=bpd.getProperty(key);
                    break;
                }
            } else if (key.startsWith("Params")&&
                       key.endsWith(".SegHead.code")) {

                String code2=bpd.getProperty(key);
                if (paramCode.equals(code2)) {
                    isGV=true;
                }
            }
        }

        // wenn das kein GV ist, dann ist es ein Admin-Segment
        if (ret.length()==0&&!isGV) {
            if (verifyTANMode && code.equals("HKIDN")) {
                // im TAN-verify-mode wird bei der dialog-initialisierung
                // eine TAN mit versandt; die Dialog-Initialisierung erkennt
                // man am HKIDN-segment
                ret="J";
                deactivateTANVerifyMode();
            } else {
                ret="A";
            }
        }
        
        return ret;
    }

    public void deactivateTANVerifyMode()
    {
        this.verifyTANMode=false;
    }

    public void activateTANVerifyMode()
    {
        this.verifyTANMode=true;
    }

    public void setCertFile(String filename)
    {
        this.certfile=filename;
    }
    
    public String getCertFile()
    {
        return certfile;
    }
    
    protected void setCheckCert(boolean doCheck)
    {
        this.checkCert=doCheck;
    }
    
    public boolean getCheckCert()
    {
        return checkCert;
    }

    public String getProxy() 
    {
        return proxy;
    }

    public void setProxy(String proxy) 
    {
        this.proxy = proxy;
    }

    public String getProxyPass() 
    {
        return proxypass;
    }

    public String getProxyUser() 
    {
        return proxyuser;
    }

    public void setProxyPass(String proxypass) 
    {
        this.proxypass = proxypass;
    }

    public void setProxyUser(String proxyuser) 
    {
        this.proxyuser = proxyuser;
    }
    
    /**
     * Liefert den Code fuer den Hash-Modus, mit dem bei der HKTAN-Prozessvariante 1 das Auftragssegment gehasht werden soll.
     * @return der Order-Hashmode oder NULL, wenn er nicht ermittelbar ist.
     * @throws HBCI_Exception wenn ein ungueltiger Wert fuer den Hash-Mode in den BPD angegeben ist.
     */
    private String getOrderHashMode()
    {
        final Properties bpd = this.getBPD();
        if (bpd == null)
            return null;

        // Wir muessen auch bei der richtigen Segment-Version schauen
        final Properties props = this.getCurrentSecMechInfo();
        final String segVersion = props.getProperty("segversion");
        
        final String s = ParameterFinder.getValue(bpd,Query.BPD_PINTAN_ORDERHASHMODE.withParameters((segVersion != null ? segVersion : "")),null);
        
        if ("1".equals(s))
            return DigestUtils.ALG_RIPE_MD160;
        if ("2".equals(s))
            return DigestUtils.ALG_SHA1;
                    
        throw new HBCI_Exception("unknown orderhash mode " + s);
    }
    
    /**
     * Patcht die TAN-Abfrage bei Bedarf in die Nachricht.
     * Hinweis: Wir haben das ganze HKTAN-Handling derzeit leider doppelt. Einmal fuer die Dialog-Initialisierung (checkSCAResponse) und einmal fuer
     * die Nachrichten mit den eigentlichen Geschaeftsvorfaellen (in patchMessagesFor2StepMethods). Wenn auch HBCIDialog#doJobs irgendwann
     * auf die neuen RawHBCIDialoge umgestellt ist, kann eigentlich patchMessagesFor2StepMethods entfallen.
     * @param dialog der Dialog.
     * @param ret der aktuelle Dialog-Status.
     */
    private void patchMessagesFor2StepMethods(DialogContext ctx)
    {
        final HBCIDialog dialog = ctx.getDialog();
        if (dialog == null)
            return;
        
        final HBCIMessageQueue queue = dialog.getMessageQueue();
        if (queue == null)
            return;
        
        // Einschritt-Verfahren - kein HKTAN erforderlich
        final String tanMethod = this.getCurrentTANMethod(false);
        if (tanMethod.equals(TanMethod.ONESTEP.getId()))
            return;

        // wenn es sich um das pintan-verfahren im zweischritt-modus handelt,
        // müssen evtl. zusätzliche nachrichten bzw. segmente eingeführt werden
        HBCIUtils.log("patching message for twostep method",HBCIUtils.LOG_DEBUG);
        
        final HBCIHandler handler    = (HBCIHandler) this.getParentHandlerData();
        final Properties secmechInfo = this.getCurrentSecMechInfo();
        final String segversion      = secmechInfo.getProperty("segversion");
        final String process         = secmechInfo.getProperty("process");

        for (HBCIMessage message:queue.getMessages())
        {
            for (HBCIJobImpl task:message.getTasks())
            {
                // Damit wir keine doppelten erzeugen
                if (task.haveTan())
                    continue;
                
                final String segcode = task.getHBCICode();
                
                // Braucht der Job eine TAN?
                if (!this.getPinTanInfo(segcode).equals("J"))
                {
                    HBCIUtils.log("found task that does not require HKTAN: " + segcode + " - adding it to current msg",HBCIUtils.LOG_DEBUG);
                    continue;
                }
    
                // OK, Task braucht vermutlich eine TAN - es handelt sich um einen tan-pflichtigen task
                // Ob letztlich tatsaechlich beim User eine TAN-Abfrage ankommt, haengt davon ab, ob die Bank ggf. eine 3076 SCA-Ausnahme sendet
                HBCIUtils.log("found task that probably requires HKTAN: " + segcode + " - have to patch message queue",HBCIUtils.LOG_DEBUG);
                
                final GVTAN2Step hktan = (GVTAN2Step) handler.newJob("TAN2Step");
                hktan.setParam("ordersegcode",task.getHBCICode()); // Seit HKTAN auch bei HKTAN#6 Pflicht
                hktan.setExternalId(task.getExternalId()); // externe ID durchreichen
                hktan.setSegVersion(segversion); // muessen wir explizit setzen, damit wir das HKTAN in der gleichen Version schicken, in der das HITANS kam.
                task.tanApplied();
                
                final String tanMedia = this.getTanMedia(Integer.parseInt(hktan.getSegVersion()));
                if (tanMedia != null && tanMedia.length() > 0) // tanmedia nur setzen, wenn vorhanden Sonst meckert HBCIJobIml
                    hktan.setParam("tanmedia",tanMedia);
                
                
                ////////////////////////////////////////////////////////////////////////////
                // Prozess-Variante 1:
                // 1. Nur HKTAN mit dem Hash des Auftragssegments einreichen, dann per HITAN die TAN generieren
                // 2. Auftrag + TAN (HNSHA) einreichen
                if (process.equals("1"))
                {
                    HBCIUtils.log("process variant 1: adding new message with HKTAN(p=1,hash=...) before current message",HBCIUtils.LOG_DEBUG);
                    hktan.setProcess(KnownTANProcess.PROCESS1);
                    hktan.setParam("notlasttan","N");
                    
                    // willuhn 2011-05-16
                    // Siehe FinTS_3.0_Security_Sicherheitsverfahren_PINTAN_Rel_20101027_final_version.pdf, Seite 58
                    int hktanVersion = Integer.parseInt(hktan.getSegVersion());
                    if (hktanVersion >= 5)
                    {
                      // Zitat aus HITANS5: Diese Funktion ermöglicht das Sicherstellen einer gültigen Kontoverbindung
                      // z. B. für die Abrechnung von SMS-Kosten bereits vor Erzeugen und Versenden einer
                      // (ggf. kostenpflichtigen!) TAN.
                      //  0: Auftraggeberkonto darf nicht angegeben werden
                      //  2: Auftraggeberkonto muss angegeben werden, wenn im Geschäftsvorfall enthalten
                      String noa = secmechInfo.getProperty("needorderaccount","");
                      HBCIUtils.log("needorderaccount=" + noa,HBCIUtils.LOG_DEBUG);
                      if (noa.equals("2"))
                      {
                        Konto k = task.getOrderAccount();
                        if (k != null)
                        {
                            HBCIUtils.log("applying orderaccount to HKTAN for " + task.getHBCICode(),HBCIUtils.LOG_DEBUG);
                            hktan.setParam("orderaccount",k);
                        }
                        else
                        {
                            HBCIUtils.log("orderaccount needed, but not found in " + task.getHBCICode(),HBCIUtils.LOG_WARN);
                        }
                      }
                    }
    
                    // Challenge-Klasse, wenn erforderlich
                    if (secmechInfo.getProperty("needchallengeklass","N").equals("J"))
                    {
                        ChallengeInfo cinfo = ChallengeInfo.getInstance();
                        cinfo.applyParams(task,hktan,secmechInfo);
                    }
    
                    // orderhash ermitteln
                    SEG seg = null;
                    try
                    {
                        seg = task.createJobSegment(3); // FIXME: hartcodierte Segment-Nummer. Zu dem Zeitpunkt wissen wir sie noch nicht.
                        seg.validate();
                        final String segdata = seg.toString(0);
                        HBCIUtils.log("calculating hash for jobsegment: " + segdata,HBCIUtils.LOG_DEBUG2);
                        hktan.setParam("orderhash",DigestUtils.hash(segdata,this.getOrderHashMode()));
                    }
                    finally
                    {
                        SEGFactory.getInstance().unuseObject(seg);
                    }
    
                    // HKTAN in einer neuen Nachricht *vor* dem eigentlichen Auftrag einreihen
                    HBCIMessage newMsg = queue.insertBefore(message);
                    newMsg.append(hktan);
                }
                //
                ////////////////////////////////////////////////////////////////////////////
                
                ////////////////////////////////////////////////////////////////////////////
                // Prozess-Variante 2:
                // 1. Auftrag + HKTAN einreichen, dann per HITAN die TAN generieren
                // 2. HKTAN mit Referenz zum Auftrag und TAN(HNSHA) einreichen
                else
                {
                    HBCIUtils.log("process variant 2: adding new task HKTAN(p=4) to current message",HBCIUtils.LOG_DEBUG);
                    hktan.setProcess(KnownTANProcess.PROCESS2_STEP1);
    
                    // das HKTAN direkt dahinter - in der selben Nachricht
                    message.append(hktan);
                    
                    // Neue Nachricht fuer das zweite HKTAN
                    HBCIUtils.log("process variant 2: creating new msg with HKTAN(p=2,orderref=DELAYED)",HBCIUtils.LOG_DEBUG);
                    
                    // HKTAN-job für das einreichen der TAN erzeugen
                    final GVTAN2Step hktan2 = (GVTAN2Step) handler.newJob("TAN2Step");
                    hktan2.setProcess(KnownTANProcess.PROCESS2_STEP2);
                    hktan2.setExternalId(task.getExternalId()); // externe ID auch an HKTAN2 durchreichen
                    hktan2.setSegVersion(segversion);
                    hktan2.setParam("notlasttan","N");
    
                    // in dem zweiten HKTAN-Job eine referenz auf den originalen job
                    // speichern, damit die antwortdaten für den job, die als antwortdaten
                    // für hktan2 ankommen, dem richtigen job zugeordnet werden können
                    HBCIUtils.log("storing reference to original job in new HKTAN segment",HBCIUtils.LOG_DEBUG);
                    hktan2.setTask(task);
    
                    // in dem ersten HKTAN-job eine referenz auf den zweiten speichern,
                    // damit der erste die auftragsreferenz später im zweiten speichern kann
                    hktan.setStep2(hktan2);
    
                    // Dahinter eine neue Nachricht mit dem einzelnen HKTAN#2
                    HBCIUtils.log("adding new message with HKTAN(p=2) after current one",HBCIUtils.LOG_DEBUG);
                    HBCIMessage newMsg = queue.insertAfter(message);
                    newMsg.append(hktan2);
                }
            }
        }
    }
    
    /**
     * Uebernimmt das Rueckfragen der TAN-Medien-Bezeichung bei Bedarf.
     * @param segVersion die HKTAN-Versionsnummer.
     * @return das ausgewaehlte TAN-Medium oder einen Leerstring, wenn keines verfuegbar war oder keines noetig ist (bei HKTAN < 3).
     */
    private String getTanMedia(int segVersion)
    {
        // Gibts erst ab hhd1.3, siehe
        // FinTS_3.0_Security_Sicherheitsverfahren_PINTAN_Rel_20101027_final_version.pdf, Kapitel B.4.3.1.1.1
        // Zitat: Ist in der BPD als Anzahl unterstützter aktiver TAN-Medien ein Wert > 1
        //        angegeben und ist der BPD-Wert für Bezeichnung des TAN-Mediums erforderlich = 2,
        //        so muss der Kunde z. B. im Falle des mobileTAN-Verfahrens
        //        hier die Bezeichnung seines für diesen Auftrag zu verwendenden TAN-
        //        Mediums angeben.
        // Ausserdem: "Nur bei TAN-Prozess=1, 3, 4". Das muess aber der Aufrufer pruefen. Ist mir
        // hier zu kompliziert
        HBCIUtils.log("HKTAN version: " + segVersion,HBCIUtils.LOG_DEBUG);
        if (segVersion < 3)
            return "";

        Properties  secmechInfo = getCurrentSecMechInfo();
        
        // Anzahl aktiver TAN-Medien ermitteln
        String needed = secmechInfo != null ? secmechInfo.getProperty("needtanmedia","") : "";
        HBCIUtils.log("needtanmedia: " + needed,HBCIUtils.LOG_DEBUG);
    
        // Ich hab Mails von Usern erhalten, bei denen die Angabe des TAN-Mediums auch
        // dann noetig war, wenn nur eine Handy-Nummer hinterlegt war. Daher loggen wir
        // "num" nur, bringen die Abfrage jedoch schon bei num<2 - insofern needed=2.
        
        String result = "";
        final Properties upd = this.getUPD();
        final boolean tn = needed.equals("2");
        if (tn)// && upd != null && upd.size() > 0)
        {
            HBCIUtils.log("we have to add the tan media",HBCIUtils.LOG_DEBUG);
    
            StringBuffer retData=new StringBuffer();
            if (upd != null)
                retData.append(upd.getProperty(HBCIUser.UPD_KEY_TANMEDIA,""));
            HBCIUtilsInternal.getCallback().callback(this,HBCICallback.NEED_PT_TANMEDIA,"*** Enter the name of your TAN media",HBCICallback.TYPE_TEXT,retData);
            result = retData.toString();
        }

        if (result != null && result.length() > 0)
            return result;
        
        // Seit HKTAN 6: Wenn die Angabe eines TAN-Mediennamens laut BPD erforderlich ist, wir aber gar keinen Namen haben,
        // dann "noref" eintragen.
        return tn ? "noref" : "";
    }
    
    public void setPIN(String pin)
    {
        this.pin=pin;
    }
    
    public String getPIN()
    {
        return this.pin;
    }
    
    public void clearPIN()
    {
        setPIN(null);
    }
    
    public List<String> getAllowedTwostepMechanisms() 
    {
        return this.allowedTANMethods;
    }
    
    public void setAllowedTwostepMechanisms(List<String> l)
    {
        this.allowedTANMethods=l;
    }
    
    public int getMaxGVSegsPerMsg()
    {
        return 1;
    }
    
    /**
     * Ueberschrieben, um das "https://" am Anfang automatisch abzuschneiden.
     * Das sorgte schon fuer so viele unnoetige Fehler.
     * @see org.kapott.hbci.passport.AbstractHBCIPassport#getHost()
     */
    @Override
    public String getHost()
    {
      String host = super.getHost();
      if (host == null || host.length() == 0 || !host.startsWith("https://"))
        return host;
      
      return host.replace("https://","");
    }
}