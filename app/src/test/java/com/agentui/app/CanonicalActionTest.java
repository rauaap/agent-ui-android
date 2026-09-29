package com.agentui.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CanonicalActionTest {
    private static CanonicalAction action(String json) throws Exception {
        return CanonicalAction.parse(new JSONObject(json));
    }

    @Test public void acceptsEveryCanonicalKind() throws Exception {
        String[] values = {
                "{kind:'command',command:'git status',description:'Status',timeout_ms:1000,shell:'bash'}",
                "{kind:'read',path:'src/Main.java',offset:1,limit:20}",
                "{kind:'edit',path:'a',edits:[{old_text:'x',new_text:'',replace_all:false}]}",
                "{kind:'write',path:'empty',content:''}",
                "{kind:'search',mode:'content',query:'needle',path:'src',glob:'*.java',limit:5}",
                "{kind:'list',limit:20}",
                "{kind:'web',operation:'fetch',url:'https://example.com',prompt:'Summarize'}",
                "{kind:'task',description:'Inspect auth',prompt:'Find tokens',agent:'Explore'}",
                "{kind:'other',name:'Deploy',arguments:{environment:'staging'}}"
        };
        for (String value : values) assertNotNull(value, action(value));
    }

    @Test public void canonicalProvidersHaveIdenticalPresentation() throws Exception {
        CanonicalAction claude = action("{kind:'edit',path:'a',edits:[{old_text:'x',new_text:'y'}]}");
        CanonicalAction pi = action("{kind:'edit',path:'a',edits:[{old_text:'x',new_text:'y'}]}");
        assertEquals("edit", claude.title());
        assertEquals(claude.summary(), pi.summary());
        assertEquals(claude.json().toString(), pi.json().toString());
    }

    @Test public void rejectsMalformedAndFutureActions() throws Exception {
        String[] values = {
                "{kind:'command',command:4}",
                "{kind:'read',path:'a',offset:1.5}",
                "{kind:'edit',path:'a',edits:[]}",
                "{kind:'edit',path:'a',edits:[{old_text:'',new_text:'x'}]}",
                "{kind:'write',path:'',content:'x'}",
                "{kind:'search',mode:'other',query:'x'}",
                "{kind:'web',operation:'fetch',url:'https://x',query:'forbidden'}",
                "{kind:'other',name:'x',arguments:[]}",
                "{kind:'future',value:'x'}",
                "{kind:'list',path:null}",
                "{kind:'list',extra:true}"
        };
        for (String value : values) assertNull(value, action(value));
    }

    @Test public void otherUsesOnlyOpaqueArgumentsAsDetail() throws Exception {
        CanonicalAction other = action("{kind:'other',name:'Deploy',arguments:{target:'prod'}}");
        assertNotNull(other);
        assertEquals("Deploy", other.title());
        assertEquals("prod", other.detail().getString("target"));
    }

    @Test public void hostCommandLabelsEveryArgumentIncludingTimeout() throws Exception {
        CanonicalAction.HostCommand host = action("{kind:'other',name:'Execute outside sandbox',"
                + "arguments:{command:'podman build .',reason:'Needs podman',timeout_seconds:600,cwd:'/w'}}")
                .hostCommand();
        assertNotNull(host);
        assertEquals("podman build .", host.command);
        assertEquals("Needs podman", host.reason);
        assertEquals("/w", host.cwd);
        assertEquals("10 min", host.timeout);

        CanonicalAction.HostCommand fractional = action("{kind:'other',name:'Execute outside sandbox',"
                + "arguments:{command:'ls',reason:'r',timeout_seconds:0.5,cwd:'/w'}}").hostCommand();
        assertEquals("0.5 s", fractional.timeout);

        CanonicalAction.HostCommand older = action("{kind:'other',name:'Execute outside sandbox',"
                + "arguments:{command:'ls',reason:'r',cwd:'/w'}}").hostCommand();
        assertEquals("server default", older.timeout);
    }

    @Test public void hostCommandFallsBackToRawJsonWhenNotUnderstood() throws Exception {
        String[] values = {
                "{kind:'other',name:'Deploy',arguments:{command:'ls',timeout_seconds:1}}",
                "{kind:'other',name:'Execute outside sandbox',arguments:{command:'ls',timeout_seconds:'120'}}",
                "{kind:'other',name:'Execute outside sandbox',arguments:{command:'ls',timeout_seconds:0}}",
                "{kind:'other',name:'Execute outside sandbox',arguments:{command:'ls',timeout_seconds:-5}}",
                "{kind:'other',name:'Execute outside sandbox',arguments:{command:'ls',env:'X=1'}}",
                "{kind:'other',name:'Execute outside sandbox',arguments:{reason:'r'}}",
                "{kind:'command',command:'ls'}"
        };
        for (String value : values) assertNull(value, action(value).hostCommand());
    }

    @Test public void formatsDurations() {
        assertEquals("< 0.001 s", CanonicalAction.duration(0.0001));
        assertEquals("0.5 s", CanonicalAction.duration(0.5));
        assertEquals("45 s", CanonicalAction.duration(45));
        assertEquals("1 min", CanonicalAction.duration(59.9999));
        assertEquals("2 min", CanonicalAction.duration(120));
        assertEquals("1 min 30.25 s", CanonicalAction.duration(90.25));
        assertEquals("1 h", CanonicalAction.duration(3600));
        assertEquals("1 h 30 min", CanonicalAction.duration(5400));
        assertEquals("2 h 5 s", CanonicalAction.duration(7205));
        assertEquals("100000000 h", CanonicalAction.duration(3.6e11));
    }

    @Test public void validatesApprovalOptionsIncludingEmptyFallback() throws Exception {
        assertTrue(CanonicalAction.validOptions(new JSONArray("[]")));
        assertTrue(CanonicalAction.validOptions(new JSONArray(
                "[{id:'allow',name:'Allow',kind:'allow_once'}]")));
        assertEquals(false, CanonicalAction.validOptions(new JSONArray(
                "[{id:'',name:'Allow',kind:'allow_once'}]")));
        assertEquals(false, CanonicalAction.validOptions(new JSONArray(
                "[{id:'allow',name:'Allow',kind:'unknown'}]")));
    }
}
