/**********************************************************************
 *
 * This file is part of HBCI4Java.
 * Copyright (c) 2001-2008 Stefan Palme
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 *
 **********************************************************************/

package org.kapott.hbci.status;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;

import org.kapott.hbci.dialog.KnownReturncode;
import org.kapott.hbci.manager.HBCIUtilsInternal;

/** <p>Enthält alle Status-Informationen zu genau einem Nachrichtenaustausch.
    Es ist zu beachten, dass in einer Nachricht Informationen zu
    <em>mehreren</em> Geschäftsvorfällen enthalten sein können, wenn die
    gesendete Nachricht mehrere Aufträge enthalten hat.
    </p><p>
    Die direkte Auswertung
    der Felder dieser Klasse wird nicht empfohlen, statt dessen sollten nur
    die Methoden benutzt werden, die den prinzipiellen Status (OK oder nicht OK)
    sowie die eigentlichen Fehler-Informationen zurückgeben. </p>*/
public final class HBCIMsgStatus
{
    /** Globale Status-Informationen. Das sind Informationen, die die
        Nachricht als ganzes betreffen (z.B. wenn die Nachricht nicht signiert
        oder verschlüsselt war, oder wenn sie nicht dekodiert werden konnte etc.) */
    public HBCIStatus globStatus;
    /** Status-Informationen, die einzelne Segmente der Nachricht betreffen.
        Hier werden alle Rückgabecodes gespeichert, die sich konkret auf
        einzelne Segmente der gesendeten Nachricht beziehen. */
    public HBCIStatus segStatus;
    
    private Properties data;
    
    public HBCIMsgStatus()
    {
        this.globStatus=new HBCIStatus();
        this.segStatus=new HBCIStatus();
        this.data=new Properties();
    }
    
    /** Wird von der <em>HBCI4Java</em>-Dialog-Engine aufgerufen */
    public void addException(Exception e)
    {
        globStatus.addException(e);
    }
    
    /** Wird von der <em>HBCI4Java</em>-Dialog-Engine aufgerufen */
    public void setData(Properties data)
    {
        this.data=data;
        extractStatusData();
    }
    
    /** Wird von der <em>HBCI4Java</em>-Dialog-Engine aufgerufen */
    public void addData(Properties _data)
    {
        this.data.putAll(_data);
        extractStatusData();
    }
     
    private void extractStatusData()
    {
        this.globStatus=new HBCIStatus();
        this.segStatus=new HBCIStatus();

        // globale return-codes extrahieren
        for (int i=0;true;i++) {
            HBCIRetVal rv=null;
            try {
                rv=new HBCIRetVal(data,
                                  HBCIUtilsInternal.withCounter("RetGlob.RetVal",i));
            } catch (Exception e) {
                break;
            }
            
            globStatus.addRetVal(rv);
        }
        
        // segment-codes extrahieren
        for (int i=0;true;i++) {
            String segheader=HBCIUtilsInternal.withCounter("RetSeg",i);
            String segref=data.getProperty(segheader+".SegHead.ref");
            if (segref==null) {
                break;
            }
            
            for (int j=0;true;j++) {
                HBCIRetVal rv=null;
                try {
                    rv=new HBCIRetVal(data,
                                      HBCIUtilsInternal.withCounter(segheader+".RetVal",j),
                                      segref);
                } catch (Exception e) {
                    break;
                }
                
                segStatus.addRetVal(rv);
            }
        }
    }
    
    /** <p>Gibt den eigentlichen Inhalt sowohl der gesendeten wie auch der
        empfangenen Nachricht zurück. Die <em>keys</em> des Property-Objektes
        enthalten die Lowlevelnamen der Datenelemente, die dazugehörigen
        <em>values</em> enthalten jeweils den Wert des entsprechenden Datenelementes.
        Die Bezeichnungen der Datenelemente der <em>gesendeten</em> Nachricht tragen
        zur Unterscheidung mit den Datenelementen der empfangenen Nachricht das
        Prefix "<code>orig_</code>".</p> */
    public Properties getData()
    {
        return data;
    }
    
    /** Gibt zurück, ob bei der Ausführung eines Nachrichtenaustauschs Exceptions
        aufgetreten sind. Diese Exceptions können entweder beim Erzeugen bzw.
        Versenden der Kundennachricht oder aber beim Empfangen und Auswerten
        der Institutsnachricht aufgetreten sein.
        @return <code>true</code>, wenn Exceptions aufgetreten sind, sonst
                <code>false</code>*/
    public boolean hasExceptions()
    {
        return globStatus.hasExceptions();
    }
    
    /** Gibt die Exceptions zurück, ob bei der Ausführung eines 
        Nachrichtenaustauschs aufgetreten sind. Diese Exceptions können entweder 
        beim Erzeugen bzw. Versenden der Kundennachricht oder aber beim Empfangen 
        und Auswerten der Institutsnachricht aufgetreten sein.
        @return Array mit aufgetretenen Exceptions, ist niemals <code>null</code>,
                 kann aber die Länge 0 haben */
    public Exception[] getExceptions()
    {
        return globStatus.getExceptions();
    }

    /** Gibt zurück, ob ein Nachrichtenaustausch erfolgreich durchgeführt wurde. Das
        ist dann der Fall, wenn bei der Abarbeitung keine Exceptions aufgetreten
        sind und die Antwortnachricht eine Erfolgsmeldung oder zumindest
        nur Warnungen (aber keine Fehlermeldung) enthält.
        @return <code>true</code>, wenn die Nachricht erfolgreich abgearbeitet
                wurde, sonst <code>false</code> */
    public boolean isOK()
    {
        // wenn bei einer CustomMsg mit mehreren GVs EINER fehlschlug,
        // dann ist auch isOK()==false. Ist das so gewollt?
        return globStatus.getStatusCode()==HBCIStatus.STATUS_OK;
    }
    
    /** Gibt einen String zurück, der alle aufgetretenen Fehler bei der 
        Durchführung des Nachrichtenaustauschs beschreibt. Dieser String besteht aus
        allen Exception-Meldungen sowie allen evtl. empfangenen Fehlermeldungen.
        Die Meldungen werden aus den einzelnen 
        {@link org.kapott.hbci.status.HBCIStatus}-Objekten durch 
        Aufruf der Methode {@link org.kapott.hbci.status.HBCIStatus#getErrorString()}
        erzeugt. 
        @return String mit allen aufgetretenen Fehlermeldungen */
    public String getErrorString()
    {
        StringBuffer ret=new StringBuffer();
        
        ret.append(globStatus.getErrorString());
        ret.append(System.getProperty("line.separator"));
        ret.append(segStatus.getErrorString());
        
        return ret.toString().trim();
    }
    
    /** Fasst alle Status-Informationen zu einem Nachrichtenaustausch in einem einzigen
        String zusammen und gibt diesen zurück. Dazu gehören alle evtl. 
        aufgetretenen Exception-Meldungen, alle Fehlermeldungen, Warnungen sowie
        Erfolgsmeldungen. Die Meldungen werden aus den einzelnen 
        {@link org.kapott.hbci.status.HBCIStatus}-Objekten durch 
        Aufruf der Methode {@link org.kapott.hbci.status.HBCIStatus#toString()}
        erzeugt. 
        @return einen String, der alle Status-Informationen zu einer Nachricht enthält */
    public String toString()
    {
        StringBuffer ret=new StringBuffer();
        ret.append(globStatus.toString());
        ret.append(System.getProperty("line.separator"));
        ret.append(segStatus.toString());
        return ret.toString().trim();
    }
    
    /** 
     * Gibt zurück, ob der Fehler "PIN ungültig" zurückgemeldet wurde
     * @return <code>true</code> oder <code>false</code> */
    public boolean isInvalidPIN()
    {
        return this.getInvalidPINCode() != null;
    }
    
    /**
     * Liefert den Status-Code fuer "PIN falsch", insofern er im Response enthalten ist.
     * @return der Status-Code fuer "PIN falsch", insofern er im Response enthalten ist.
     */
    public HBCIRetVal getInvalidPINCode()
    {
        List<HBCIRetVal> retvals=new ArrayList<HBCIRetVal>(Arrays.asList(globStatus.getErrors()));
        retvals.addAll(new ArrayList<HBCIRetVal>(Arrays.asList(segStatus.getErrors())));
        
        for (Iterator<HBCIRetVal> i=retvals.iterator(); i.hasNext();) {
            HBCIRetVal ret=i.next();
            
            if (KnownReturncode.contains(ret.code,KnownReturncode.LIST_AUTH_FAIL))
                return ret;
        }
        
        return null;
    }
}
