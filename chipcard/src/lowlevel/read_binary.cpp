/**********************************************************************
 *
 * This file is part of HBCI4Java.
 * Copyright (c) 2001-2007 Stefan Palme
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

#include <stdlib.h>
#include <string.h>

#include "ctapi-tools.h"
#include "seccos.h"

bool SECCOS_readBinary(size_t *size,unsigned char *buffer,unsigned short offset,unsigned char maxsize)
{
    unsigned char command[]=
    {
        SECCOS_CLA_STD,
        SECCOS_INS_READ_BINARY,
        (offset>>8)&0xFF,
        (offset>>0)&0xFF,
        maxsize,
    };
    unsigned short int len=300;
    unsigned char      *response=new unsigned char[len];
    
    unsigned short int status=CTAPI_performWithCard("readBinary",5,command,&len,response);
    
    if (CTAPI_isOK(status)) {
        *size=len-2;
        memcpy(buffer,response,*size);
        delete response;
        return true;
    } else {
        delete response;
        return false;
    }
}

bool SECCOS_readBinary(size_t *size,unsigned char *buffer,unsigned char maxsize)
{
    return SECCOS_readBinary(size,buffer,0,maxsize);
}

bool SECCOS_readBinary(size_t *size,unsigned char *buffer)
{
    return SECCOS_readBinary(size,buffer,0,0);
}
