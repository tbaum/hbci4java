package org.kapott.hbci.passport;

import org.kapott.hbci.callback.HBCICallback;
import org.kapott.hbci.exceptions.HBCI_Exception;
import org.kapott.hbci.manager.FlickerCode;
import org.kapott.hbci.manager.HBCIUtils;
import org.kapott.hbci.manager.HBCIUtilsInternal;
import org.kapott.hbci.manager.LogFilter;
import org.kapott.hbci.security.Sig;

import java.io.Closeable;
import java.util.Properties;
import java.util.StringTokenizer;

public class HBCIMemoryPassportPinTan extends AbstractPinTanPassport implements Closeable {


    public HBCIMemoryPassportPinTan(Object initObject) {
        super(initObject);
        boolean init = HBCIUtils.getParam("client.passport.PinTan.init", "1").equals("1");

        setCertFile(HBCIUtils.getParam("client.passport.PinTan.certfile"));
        setCheckCert(HBCIUtils.getParam("client.passport.PinTan.checkcert", "1").equals("1"));

        setProxy(HBCIUtils.getParam("client.passport.PinTan.proxy", ""));
        setProxyUser(HBCIUtils.getParam("client.passport.PinTan.proxyuser", ""));
        setProxyPass(HBCIUtils.getParam("client.passport.PinTan.proxypass", ""));

        if (init) {
            saveChanges();
            if (askForMissingData(true, true, true, true, true, true, true))
                saveChanges();
        }
    }


    public void resetPassphrase() {
    }

    public void saveChanges() {
        getCurrentTANMethod(false);
    }

    public byte[] hash(byte[] data) {
        /* there is no hashing before signing, so we return the original message,
         * which will later be "signed" by sign() */
        return data;
    }

    public byte[] sign(byte[] data) {
        try {
            // TODO: wenn die eingegebene PIN falsch war, muss die irgendwie
            // resettet werden, damit wieder danach gefragt wird
            if (getPIN() == null) {
                StringBuffer s = new StringBuffer();

                HBCIUtilsInternal.getCallback().callback(this,
                        HBCICallback.NEED_PT_PIN,
                        HBCIUtilsInternal.getLocMsg("CALLB_NEED_PTPIN"),
                        HBCICallback.TYPE_SECRET,
                        s);
                if (s.length() == 0) {
                    throw new HBCI_Exception(HBCIUtilsInternal.getLocMsg("EXCMSG_PINZERO"));
                }
                setPIN(s.toString());
                LogFilter.getInstance().addSecretData(getPIN(), "X", LogFilter.FILTER_SECRETS);
            }

            String tan = "";

            // tan darf nur beim einschrittverfahren oder bei 
            // PV=1 und passport.contains(challenge)           und tan-pflichtiger auftrag oder bei
            // PV=2 und passport.contains(challenge+reference) und HKTAN
            // ermittelt werden

            String pintanMethod = getCurrentTANMethod(false);

            if (pintanMethod.equals(Sig.SECFUNC_SIG_PT_1STEP)) {
                // nur beim normalen einschritt-verfahren muss anhand der segment-
                // codes ermittelt werden, ob eine tan ben�tigt wird
                HBCIUtils.log("onestep method - checking GVs to decide whether or not we need a TAN", HBCIUtils.LOG_DEBUG);

                // segment-codes durchlaufen
                String codes = collectSegCodes(new String(data, "ISO-8859-1"));
                StringTokenizer tok = new StringTokenizer(codes, "|");

                while (tok.hasMoreTokens()) {
                    String code = tok.nextToken();
                    String info = getPinTanInfo(code);

                    if (info.equals("J")) {
                        // f�r dieses segment wird eine tan ben�tigt
                        HBCIUtils.log("the job with the code " + code + " needs a TAN", HBCIUtils.LOG_DEBUG);

                        if (tan.length() == 0) {
                            // noch keine tan bekannt --> callback

                            StringBuffer s = new StringBuffer();
                            HBCIUtilsInternal.getCallback().callback(this,
                                    HBCICallback.NEED_PT_TAN,
                                    HBCIUtilsInternal.getLocMsg("CALLB_NEED_PTTAN"),
                                    HBCICallback.TYPE_TEXT,
                                    s);
                            if (s.length() == 0) {
                                throw new HBCI_Exception(HBCIUtilsInternal.getLocMsg("EXCMSG_TANZERO"));
                            }
                            tan = s.toString();
                        } else {
                            HBCIUtils.log("there should be only one job that needs a TAN!", HBCIUtils.LOG_WARN);
                        }

                    } else if (info.equals("N")) {
                        HBCIUtils.log("the job with the code " + code + " does not need a TAN", HBCIUtils.LOG_DEBUG);

                    } else if (info.length() == 0) {
                        // TODO: ist das hier dann nicht ein A-Segment? In dem Fall
                        // w�re diese Warnung �berfl�ssig
                        HBCIUtils.log("the job with the code " + code + " seems not to be allowed with PIN/TAN", HBCIUtils.LOG_WARN);
                    }
                }
            } else {
                HBCIUtils.log("twostep method - checking passport(challenge) to decide whether or not we need a TAN", HBCIUtils.LOG_DEBUG);
                Properties secmechInfo = getCurrentSecMechInfo();

                // gespeicherte challenge aus passport holen
                String challenge = (String) getPersistentData("pintan_challenge");
                setPersistentData("pintan_challenge", null);

                // willuhn 2011-05-27 Wir versuchen, den Flickercode zu ermitteln und zu parsen
                String hhduc = (String) getPersistentData("pintan_challenge_hhd_uc");
                setPersistentData("pintan_challenge_hhd_uc", null); // gleich wieder aus dem Passport loeschen
                String flicker = parseFlickercode(challenge, hhduc);

                if (challenge == null) {
                    // es gibt noch keine challenge
                    HBCIUtils.log("will not sign with a TAN, because there is no challenge", HBCIUtils.LOG_DEBUG);
                } else {
                    HBCIUtils.log("found challenge in passport, so we ask for a TAN", HBCIUtils.LOG_DEBUG);
                    // es gibt eine challenge, also damit tan ermitteln

                    // willuhn 2011-05-27: Flicker-Code uebergeben, falls vorhanden
                    // bei NEED_PT_SECMECH wird das auch so gemacht.
                    StringBuffer s = flicker != null ? new StringBuffer(flicker) : new StringBuffer();
                    HBCIUtilsInternal.getCallback().callback(this,
                            HBCICallback.NEED_PT_TAN,
                            secmechInfo.getProperty("name") + "\n" + secmechInfo.getProperty("inputinfo") + "\n\n" + challenge,
                            HBCICallback.TYPE_TEXT,
                            s);
                    if (s.length() == 0) {
                        throw new HBCI_Exception(HBCIUtilsInternal.getLocMsg("EXCMSG_TANZERO"));
                    }
                    tan = s.toString();
                }
            }
            if (tan.length() != 0) {
                LogFilter.getInstance().addSecretData(tan, "X", LogFilter.FILTER_SECRETS);
            }

            return (getPIN() + "|" + tan).getBytes("ISO-8859-1");
        } catch (Exception ex) {
            throw new HBCI_Exception("*** signing failed", ex);
        }
    }

    /**
     * Versucht, aus Challenge und Challenge HHDuc den Flicker-Code zu extrahieren
     * und ihn in einen flickerfaehigen Code umzuwandeln.
     * Nur wenn tatsaechlich ein gueltiger Code enthalten ist, der als
     * HHDuc-Code geparst und in einen Flicker-Code umgewandelt werden konnte,
     * liefert die Funktion den Code. Sonst immer NULL.
     *
     * @param challenge der Challenge-Text. Das DE "Challenge HHDuc" gibt es
     *                  erst seit HITAN4. Einige Banken haben aber schon vorher optisches chipTAN
     *                  gemacht. Die haben das HHDuc dann direkt im Freitext des Challenge
     *                  mitgeschickt (mit String-Tokens zum Extrahieren markiert). Die werden vom
     *                  FlickerCode-Parser auch unterstuetzt.
     * @param hhduc     das echte Challenge HHDuc.
     * @return der geparste und in Flicker-Format konvertierte Code oder NULL.
     */
    private String parseFlickercode(String challenge, String hhduc) {
        // 1. Prioritaet hat hhduc. Gibts aber erst seit HITAN4
        if (hhduc != null && hhduc.trim().length() > 0) {
            try {
                FlickerCode code = new FlickerCode(hhduc);
                return code.render();
            } catch (Exception e) {
                HBCIUtils.log("unable to parse Challenge HHDuc " + hhduc + ":" + HBCIUtils.exception2String(e), HBCIUtils.LOG_DEBUG);
            }
        }

        // 2. Checken, ob im Freitext-Challenge was parse-faehiges steht.
        // Kann seit HITAN1 auftreten
        if (challenge != null && challenge.trim().length() > 0) {
            try {
                FlickerCode code = new FlickerCode(challenge);
                return code.render();
            } catch (Exception e) {
                // Das darf durchaus vorkommen, weil das Challenge auch bei manuellem
                // chipTAN- und smsTAN Verfahren verwendet wird, wo gar kein Flicker-Code enthalten ist.
                // Wir loggen es aber trotzdem - fuer den Fall, dass tatsaechlich ein Flicker-Code
                // enthalten ist. Sonst koennen wir das nicht debuggen.
                HBCIUtils.log("challenge contains no HHDuc (no problem in most cases):" + HBCIUtils.exception2String(e), HBCIUtils.LOG_DEBUG2);
            }
        }
        // Ne, definitiv kein Flicker-Code.
        return null;
    }

    public boolean verify(byte[] data, byte[] sig) {
        // TODO: fuer bankensignaturen fuer HITAN muss dass hier ge�ndert werden
        return true;
    }

    public byte[][] encrypt(byte[] plainMsg) {
        try {
            int padLength = plainMsg[plainMsg.length - 1];
            byte[] encrypted = new String(plainMsg, 0, plainMsg.length - padLength, "ISO-8859-1").getBytes("ISO-8859-1");
            return new byte[][]{new byte[8], encrypted};
        } catch (Exception ex) {
            throw new HBCI_Exception("*** encrypting message failed", ex);
        }
    }

    public byte[] decrypt(byte[] cryptedKey, byte[] cryptedMsg) {
        try {
            return new String(new String(cryptedMsg, "ISO-8859-1") + '\001').getBytes("ISO-8859-1");
        } catch (Exception ex) {
            throw new HBCI_Exception("*** decrypting of message failed", ex);
        }
    }
}
