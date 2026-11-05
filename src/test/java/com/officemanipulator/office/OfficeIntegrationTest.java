package com.officemanipulator.office;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.officemanipulator.claude.ClaudeOrchestrator;
import com.officemanipulator.domain.*;
import com.officemanipulator.storage.FileStorageService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="OFFICE_TEST_DB_URL",matches=".*office_audit_test.*")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "spring.datasource.url=${OFFICE_TEST_DB_URL}","spring.datasource.username=postgres","spring.datasource.password=office-test-only",
 "app.llm.anthropic.api-key=fake-test-key","app.budget.limit-usd=2.00","app.gemini.enabled=false",
 "app.storage.base-path=/tmp/office-editor-integration-files","app.office.enabled=true",
 "app.office.jwt-secret=test-secret-at-least-thirty-two-characters"})
class OfficeIntegrationTest {
 @Autowired OfficeService office; @Autowired OfficeTokens tokens; @Autowired JobRepository jobs;
 @Autowired FileStorageService storage; @Autowired JdbcTemplate db; @Autowired ObjectMapper json;
 @Autowired TestRestTemplate http;
 @MockBean OfficeDocumentClient docs; @MockBean ClaudeOrchestrator ai;
 @Autowired com.officemanipulator.config.LlmClientProvider llm;
 @BeforeEach @AfterEach void clean(){db.update("DELETE FROM office_sessions");db.update("DELETE FROM api_calls");db.update("DELETE FROM budget_log");db.update("DELETE FROM jobs");}
 Job fixture() throws Exception {
  Job j=new Job();j.setRequest("Original");j.setFormat(OutputFormat.DOCX);j.setStatus(JobStatus.DONE);j.setFileName("belge.docx");jobs.saveAndFlush(j);
  Path path=storage.resolveOutputPath(j.getId(),OutputFormat.DOCX);Files.createDirectories(path.getParent());Files.write(path,word("Original"));
  j.setFilePath(path.toString());j.setFileSize(Files.size(path));return jobs.saveAndFlush(j);
 }
 byte[] word(String text) throws Exception {try(var doc=new org.apache.poi.xwpf.usermodel.XWPFDocument();var out=new java.io.ByteArrayOutputStream()){doc.createParagraph().createRun().setText(text);doc.write(out);return out.toByteArray();}}
 void callback(UUID id,int code){callback(id,code,UUID.randomUUID());}
 void callback(UUID id,int code,UUID requestId){var s=office.session(id);int inserted=db.update("INSERT INTO office_save_requests(id,session_id) VALUES (?,?) ON CONFLICT DO NOTHING",requestId,id);if(inserted==1) db.update("UPDATE office_sessions SET last_requested=? WHERE id=?",requestId,id);var payload=Map.of("key",s.key(),"status",code,"userdata",requestId.toString(),"url","http://office-docs/cache/files/test/output.docx");office.callback(id,"Bearer "+tokens.sign(Map.of("payload",payload)),json.valueToTree(payload));}
 @Test void saveIsImmutableIdempotentAndReopensLatestAfterClose() throws Exception {
  Job original=fixture();byte[] before=Files.readAllBytes(Path.of(original.getFilePath()));
  var opened=office.open(original.getId());UUID id=(UUID)opened.get("sessionId");
  assertEquals(id,office.open(original.getId()).get("sessionId"));
  when(docs.download(anyString(),any())).thenReturn(word("Edited"));callback(id,6);
  var first=office.session(id);assertEquals(1,first.revision());assertNotEquals(original.getId(),first.savedJobId());
  assertArrayEquals(before,Files.readAllBytes(Path.of(original.getFilePath())));
  assertEquals(original.getId(),jobs.findById(first.savedJobId()).orElseThrow().getParentJobId());
  callback(id,6);assertEquals(1,office.session(id).revision());assertEquals(2,jobs.count());
  callback(id,2);assertTrue(office.session(id).closed());callback(id,6);assertEquals(1,office.session(id).revision());
  UUID reopened=(UUID)office.open(original.getId()).get("sessionId");assertNotEquals(id,reopened);
  assertEquals(first.savedJobId(),office.session(reopened).sourceJobId());
 }
 @Test void concurrentDuplicateCallbacksCreateOnlyOneRevision() throws Exception {
  UUID id=(UUID)office.open(fixture().getId()).get("sessionId");when(docs.download(anyString(),any())).thenReturn(word("Concurrent"));
  try(var pool=Executors.newFixedThreadPool(3)){
   var futures=new ArrayList<Future<?>>();for(int i=0;i<3;i++)futures.add(pool.submit(()->callback(id,6)));
   for(var future:futures)future.get(20,TimeUnit.SECONDS);
  }
  assertEquals(1,office.session(id).revision());assertEquals(2,jobs.count());
 }
 @Test void failedDownloadDoesNotAdvanceRevisionAndRecoveryClearsError() throws Exception {
  UUID id=(UUID)office.open(fixture().getId()).get("sessionId");when(docs.download(anyString(),any())).thenThrow(new IllegalStateException("failed"));
  assertThrows(RuntimeException.class,()->callback(id,6));assertEquals(0,office.session(id).revision());assertNotNull(office.session(id).error());assertEquals(1,jobs.count());
  doReturn(word("Recovered")).when(docs).download(anyString(),any());callback(id,6);assertNull(office.session(id).error());assertEquals(1,office.session(id).revision());
 }
 @Test void transportRejectsUnsignedCallbackAndWrongPurposeDownload() throws Exception {
  var opened=office.open(fixture().getId());UUID id=(UUID)opened.get("sessionId");
  assertEquals(HttpStatus.FORBIDDEN,http.postForEntity("/api/office/sessions/"+id+"/callback",Map.of("status",6),String.class).getStatusCode());
  assertEquals(HttpStatus.FORBIDDEN,http.getForEntity("/api/office/sessions/"+id+"/document?ticket="+opened.get("agentToken"),String.class).getStatusCode());
  assertEquals(HttpStatus.BAD_REQUEST,http.getForEntity("/api/office/sessions/"+id+"/document",String.class).getStatusCode());
  String ticket=tokens.ticket(id,"document",office.session(id).expiresAt().toInstant());
  assertEquals(HttpStatus.OK,http.getForEntity("/api/office/sessions/"+id+"/document?ticket="+ticket,byte[].class).getStatusCode());
 }
 @Test void olderRequestReplayCannotReplaceNewerSaveAndIdenticalContentStillAcknowledged() throws Exception {
  UUID id=(UUID)office.open(fixture().getId()).get("sessionId");UUID a=UUID.randomUUID(),b=UUID.randomUUID(),c=UUID.randomUUID();
  when(docs.download(anyString(),any())).thenReturn(word("A"));callback(id,6,a);
  when(docs.download(anyString(),any())).thenReturn(word("B"));callback(id,6,b);UUID latest=office.session(id).savedJobId();
  when(docs.download(anyString(),any())).thenReturn(word("A"));callback(id,6,a);assertEquals(latest,office.session(id).savedJobId());
  byte[] stable=word("B again");when(docs.download(anyString(),any())).thenReturn(stable);callback(id,6,c);
  int revision=office.session(id).revision();UUID d=UUID.randomUUID();callback(id,6,d);
  assertEquals(revision,office.session(id).revision());assertEquals(d,office.session(id).acknowledgedRequest());
 }
 @Test void expiredOriginalCanResumeUnexpiredSavedChild() throws Exception {
  Job j=fixture();UUID id=(UUID)office.open(j.getId()).get("sessionId");when(docs.download(anyString(),any())).thenReturn(word("Saved"));callback(id,2);
  j.setExpiresAt(java.time.OffsetDateTime.now().minusHours(1));jobs.saveAndFlush(j);
  UUID next=(UUID)office.open(j.getId()).get("sessionId");assertEquals(office.session(id).savedJobId(),office.session(next).sourceJobId());
 }
 @Test void importRejectsInvalidFileAndCreatesHistoryVisibleDocx() throws Exception {
  assertThrows(RuntimeException.class,()->office.importDocument("bad.docx","invalid".getBytes()));assertEquals(0,jobs.count());
  UUID id=office.importDocument("../../Belge.docx",word("İçe aktarılan metin"));
  Job saved=jobs.findById(id).orElseThrow();assertEquals("Belge.docx",saved.getFileName());assertNull(saved.getParentJobId());
  assertNotNull(office.open(id).get("sessionId"));
 }
 @Test void reopeningSavedChildKeepsCanonicalRootForOriginalHistory() throws Exception {
  Job original=fixture();UUID first=(UUID)office.open(original.getId()).get("sessionId");
  when(docs.download(anyString(),any())).thenReturn(word("A"));callback(first,2);UUID a=office.session(first).savedJobId();
  UUID second=(UUID)office.open(a).get("sessionId");when(docs.download(anyString(),any())).thenReturn(word("B"));callback(second,2);
  UUID b=office.session(second).savedJobId();UUID third=(UUID)office.open(original.getId()).get("sessionId");
  assertEquals(b,office.session(third).sourceJobId());assertEquals(original.getId(),office.session(second).rootJobId());
 }
 @Test void retryClearsOldErrorAndDelayedFailureCannotPoisonNewSave() throws Exception {
  UUID id=(UUID)office.open(fixture().getId()).get("sessionId");UUID old=UUID.randomUUID();callback(id,7,old);assertNotNull(office.session(id).error());
  var command=office.save(id);assertNull(office.session(id).error());UUID latest=(UUID)command.get("requestId");
  when(docs.download(anyString(),any())).thenReturn(word("New"));callback(id,6,latest);callback(id,7,old);
  assertNull(office.session(id).error());assertEquals(latest,office.session(id).acknowledgedRequest());
 }
 @Test void agentReturnsProposalWithoutChangingDocumentAndAccountsUsage() throws Exception {
  llm.activateVerified("fake-test-key","claude-sonnet-4-6"); // AI endpoints require a verified key; this test predates that gate
  Job original=fixture();var opened=office.open(original.getId());UUID id=(UUID)opened.get("sessionId");
  when(ai.runDocumentEdit(any(),anyString(),anyString())).thenAnswer(a->{Job j=a.getArgument(0);j.setTokenInput(11);j.setTokenOutput(7);j.setCostUsd(new java.math.BigDecimal("0.001"));
   return new com.officemanipulator.claude.dto.OrchestratorDocumentEditResult("{\"text\":\"Öneri\",\"formatting\":null}",11,7,new java.math.BigDecimal("0.001"));});
  HttpHeaders headers=new HttpHeaders();headers.setBearerAuth((String)opened.get("agentToken"));
  var result=http.postForEntity("/api/office/sessions/"+id+"/agent",new HttpEntity<>(Map.of("text","Original","instruction","Düzelt"),headers),String.class);
  assertEquals(HttpStatus.OK,result.getStatusCode());assertTrue(result.getBody().contains("Öneri"));assertEquals(0,office.session(id).revision());
  Job billed=jobs.findById(original.getId()).orElseThrow();assertEquals(11,billed.getTokenInput());assertEquals(7,billed.getTokenOutput());
 }
}
