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

package org.kapott.hbci.GV_Result;



/** Rückgabedaten für das Einreichen einer terminierten Überweisung. Beim Einreichen
    einer terminierten Überweisung gibt die Bank u.U. eine Auftrags-Identifikationsnummer
    zurück, die benutzt werden kann, um den Auftrag später zu ändern oder zu löschen. */
public class GVRTermUeb
    extends HBCIJobResultImpl
{
    private String orderid;

    public void setOrderId(String orderid)
    {
        this.orderid=orderid;
    }

    /** Gibt die Auftrags-ID zurück, unter der der Auftrag bei der Bank geführt wird. 
        @return die Auftrags-ID oder <code>null</code>, wenn die Bank keine Auftrags-IDs unterstützt */
    public String getOrderId()
    {
        return orderid;
    }
    
    public String toString()
    {
        return "orderid: "+getOrderId();
    }
}
