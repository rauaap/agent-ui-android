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
