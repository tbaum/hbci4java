/**********************************************************************
 *
 * This file is part of HBCI4Java.
 * Copyright (c) 2019 Olaf Willuhn
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


package org.kapott.hbci.dialog;

import org.kapott.hbci.status.HBCIMsgStatus;

/**
 * Kapselt mehrere HBCI-Nachrichten zu einem kompletten Dialog.
 * Eigentlich muesste das Interface "RawHBCIDialog" "RawHBCIMessage" heissen, weil es nur
 * eine einzelne Nachricht kapselt und keinen kompletten Dialog. Ich lass das aber jetzt mal so.
 */
public interface HBCIProcess
{
  /**
   * Fuehrt die Dialoge mit der Bank aus.
   * @param ctx der Dialog-Context.
   * @return der Ausfuehrungsstatus.
   */
  public HBCIMsgStatus execute(final DialogContext ctx);

}


