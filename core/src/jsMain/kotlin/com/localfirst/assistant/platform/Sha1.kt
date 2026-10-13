package com.localfirst.assistant.platform

/** Portable SHA-1 (JS implementation) for UiBindings' stable citation ids. */
internal object Sha1 {
    fun digest(input: ByteArray): ByteArray {
        val u8 = js("new Uint8Array(input.length)").unsafeCast<dynamic>()
        for (i in 0 until input.size) u8[i] = (input[i].toInt() and 0xFF)
        val out = js(
            "(function(b){" +
                "function rotl(n,s){return ((n<<s)|(n>>>(32-s)))>>>0;}" +
                "var l=b.length*8;" +
                "var pad=((55-(b.length%64))+64)%64+1;" +
                "var m=new Uint8Array(b.length+pad+8);" +
                "m.set(b);" +
                "m[b.length]=0x80;" +
                "var dv=new DataView(m.buffer);" +
                "dv.setUint32(m.length-4,l>>>0);" +
                "dv.setUint32(m.length-8,Math.floor(l/4294967296));" +
                "var h=[0x67452301,0xEFCDAB89,0x98BADCFE,0x10325476,0xC3D2E1F0];" +
                "var w=new Uint32Array(80);" +
                "for(var c=0;c<m.length;c+=64){" +
                "for(var i=0;i<16;i++)w[i]=dv.getUint32(c+i*4);" +
                "for(i=16;i<80;i++)w[i]=rotl(w[i-3]^w[i-8]^w[i-14]^w[i-16],1);" +
                "var a=h[0],bb=h[1],cc=h[2],d=h[3],e=h[4];" +
                "for(i=0;i<80;i++){" +
                "var f,k;" +
                "if(i<20){f=(bb&cc)|(~bb&d);k=0x5A827999;}" +
                "else if(i<40){f=bb^cc^d;k=0x6ED9EBA1;}" +
                "else if(i<60){f=(bb&cc)|(bb&d)|(cc&d);k=0x8F1BBCDC;}" +
                "else{f=bb^cc^d;k=0xCA62C1D6;}" +
                "var t=(rotl(a,5)+f+e+k+w[i])>>>0;" +
                "e=d;d=cc;cc=rotl(bb,30);bb=a;a=t;" +
                "}" +
                "h[0]=(h[0]+a)>>>0;h[1]=(h[1]+bb)>>>0;h[2]=(h[2]+cc)>>>0;h[3]=(h[3]+d)>>>0;h[4]=(h[4]+e)>>>0;" +
                "}" +
                "var o=new Uint8Array(20);" +
                "for(var j=0;j<5;j++){o[j*4]=(h[j]>>>24)&0xFF;o[j*4+1]=(h[j]>>>16)&0xFF;o[j*4+2]=(h[j]>>>8)&0xFF;o[j*4+3]=h[j]&0xFF;}" +
                "return o;" +
                "})"
        ).unsafeCast<dynamic>()(u8)
        val res = ByteArray(20)
        for (i in 0 until 20) res[i] = (out[i].unsafeCast<Int>()).toByte()
        return res
    }
}
