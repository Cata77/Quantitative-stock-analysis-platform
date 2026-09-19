package com.quantplatform.scoring.model;

import com.quantplatform.ingestion.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import static com.quantplatform.scoring.model.FrozenModel.*;

/** Fail closed at startup as well as in CI if the packaged golden outputs no longer match. */
public final class GoldenParity {
    private GoldenParity() {}
    public static String verify(FrozenModel model) {
        var identities=new ArrayList<String>();
        for(String prefix:List.of("","varied-"))try {
            String input=new ClassPathResource("model/"+prefix+"prepared.json").getContentAsString(StandardCharsets.UTF_8);
            String expected=new ClassPathResource("model/"+prefix+"expected.json").getContentAsString(StandardCharsets.UTF_8);
            var prepared=CanonicalJson.readObject(input);
            var actual=model.score(list(prepared.get("rows")).stream().map(FrozenModel::map).toList(),str(prepared.get("as_of")),"primary");
            String unrounded=prefix.isEmpty()?expected:new ClassPathResource("model/varied-unrounded.json").getContentAsString(StandardCharsets.UTF_8);
            compare(CanonicalJson.readObject(unrounded),actual,"$raw",1e-9);
            compare(serialize(CanonicalJson.readObject(expected)),serialize(actual),"$stored",1e-9);
            identities.add(CanonicalJson.sha256(unrounded));
            identities.add(CanonicalJson.sha256(input));identities.add(CanonicalJson.sha256(expected));
        }catch(java.io.IOException e){throw new IllegalStateException("Missing parity artifacts",e);}
        try {
            for(Class<?> type:List.of(FrozenModel.class,FrozenModel.Calc.class,CanonicalModelInputs.class,GoldenParity.class))
                try(var stream=type.getResourceAsStream(type.getSimpleName().equals("Calc")?"FrozenModel$Calc.class":type.getSimpleName()+".class")){
                    identities.add(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(java.util.Objects.requireNonNull(stream).readAllBytes())));
                }
        }catch(java.io.IOException|java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        return CanonicalJson.hash(SHA,CanonicalJson.write(identities));
    }
    public static void compare(Object expected,Object actual,String path,double tolerance){
        if(expected instanceof Number a && actual instanceof Number b){
            if(!Double.isFinite(b.doubleValue())||Math.abs(a.doubleValue()-b.doubleValue())>tolerance)throw new IllegalStateException(path+": "+a+" != "+b);
        }else if(expected instanceof Map<?,?> a && actual instanceof Map<?,?> b){
            if(!a.keySet().equals(b.keySet()))throw new IllegalStateException(path+": keys differ "+a.keySet()+" vs "+b.keySet());
            a.forEach((k,v)->compare(v,b.get(k),path+"."+k,tolerance));
        }else if(expected instanceof List<?> a && actual instanceof List<?> b){
            if(a.size()!=b.size())throw new IllegalStateException(path+": lengths differ");
            for(int i=0;i<a.size();i++)compare(a.get(i),b.get(i),path+"["+i+"]",tolerance);
        }else if(!Objects.equals(expected,actual))throw new IllegalStateException(path+": "+expected+" != "+actual);
    }
}

