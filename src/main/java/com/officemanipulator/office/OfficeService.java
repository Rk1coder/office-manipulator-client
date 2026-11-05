package com.officemanipulator.office;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.officemanipulator.domain.*;
import com.officemanipulator.storage.FileStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;

@Service
@ConditionalOnProperty(name="app.office.enabled",havingValue="true")
public class OfficeService {
    public static final Set<OutputFormat> EDITABLE_FORMATS=EnumSet.of(OutputFormat.DOCX,OutputFormat.PPTX,OutputFormat.XLSX,OutputFormat.CSV,OutputFormat.PDF,
        OutputFormat.ODT,OutputFormat.ODS,OutputFormat.ODP,OutputFormat.DOC,OutputFormat.TXT);
    /** Formats ONLYOFFICE cannot open at all — edited through the app's own plain-text editor instead. */
    public static final Set<OutputFormat> PLAIN_TEXT_FORMATS=EnumSet.of(OutputFormat.MD,OutputFormat.JSON,OutputFormat.XML);
    private static String onlyOfficeDocumentType(OutputFormat format) {
        return switch(format) {
            case DOCX,DOC,ODT,TXT -> "word";
            case PPTX,ODP -> "slide";
            case XLSX,CSV,ODS -> "cell";
            case PDF -> "pdf";
            case ODB,MD,JSON,XML -> throw new IllegalArgumentException("ONLYOFFICE cannot open "+format);
        };
    }
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final JobRepository jobs;
    private final FileStorageService storage;
    private final OfficeTokens tokens;
    private final OfficeDocumentClient docs;
    private final com.officemanipulator.validator.FileValidator validator;
    private final String publicUrl,appPublic,appInternal;
    public record Session(UUID id,UUID rootJobId,UUID sourceJobId,UUID savedJobId,String key,int revision,
                          OffsetDateTime savedAt,String hash,String error,boolean closed,OffsetDateTime expiresAt,UUID acknowledgedRequest,long acknowledgedSequence) {}
    public record TextDocument(UUID jobId,OutputFormat format,String content) {}
    public OfficeService(JdbcTemplate db,org.springframework.transaction.PlatformTransactionManager manager,JobRepository jobs,
        FileStorageService storage,OfficeTokens tokens,OfficeDocumentClient docs,com.officemanipulator.validator.FileValidator validator,
        @Value("${app.office.public-url}") String publicUrl,@Value("${app.office.app-public-url}") String appPublic,
        @Value("${app.office.app-internal-url}") String appInternal) {
        this.db=db;this.tx=new TransactionTemplate(manager);this.jobs=jobs;this.storage=storage;this.tokens=tokens;this.docs=docs;this.validator=validator;
        this.publicUrl=publicUrl;this.appPublic=appPublic;this.appInternal=appInternal;
    }
    private static Session row(java.sql.ResultSet r,int index) throws java.sql.SQLException {
        return new Session(r.getObject("id",UUID.class),r.getObject("root_job_id",UUID.class),r.getObject("source_job_id",UUID.class),r.getObject("saved_job_id",UUID.class),
            r.getString("document_key"),r.getInt("revision"),r.getObject("saved_at",OffsetDateTime.class),r.getString("content_hash"),r.getString("error"),r.getBoolean("closed"),r.getObject("expires_at",OffsetDateTime.class),r.getObject("acknowledged_request",UUID.class),r.getLong("acknowledged_sequence"));
    }
    public Session session(UUID id) {return session(id,false);}
    private Session session(UUID id,boolean lock) {
        var rows=db.query("SELECT * FROM office_sessions WHERE id=?"+(lock?" FOR UPDATE":""),OfficeService::row,id);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Editör oturumu bulunamadı");
        Session s=rows.getFirst();
        if(!s.expiresAt().isAfter(OffsetDateTime.now())) throw new ResponseStatusException(HttpStatus.GONE,"Editör oturumunun süresi doldu");
        return s;
    }
    public Job availableJob(UUID id) {
        Job job=jobs.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Belge bulunamadı"));
        if(!EDITABLE_FORMATS.contains(job.getFormat()) || job.getStatus()!=JobStatus.DONE || job.getFilePath()==null
            || !job.getExpiresAt().isAfter(OffsetDateTime.now()) || !Files.isRegularFile(Path.of(job.getFilePath())))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Düzenlemeye uygun belge bulunamadı");
        return job;
    }
    private static OutputFormat detectImportFormat(String name) {
        if(name==null) return null;
        String lower=name.toLowerCase(Locale.ROOT);
        for(OutputFormat format:EDITABLE_FORMATS) if(lower.endsWith("."+format.fileExtension())) return format;
        return null;
    }
    public UUID importDocument(String name,byte[] bytes) {
        OutputFormat format=detectImportFormat(name);
        if(format==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Desteklenmeyen belge türü: "+EDITABLE_FORMATS);
        try {OfficeDocumentClient.validate(bytes,format);}
        catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Geçerli bir "+format+" belgesi seçin (en fazla 100 MB)");}
        String safeName=name.replace('\\','/');safeName=safeName.substring(safeName.lastIndexOf('/')+1).replaceAll("[\\p{Cntrl}]","");
        if(safeName.length()>180) safeName="Belge."+format.fileExtension();
        final String fileName=safeName;
        final OutputFormat finalFormat=format;
        return tx.execute(transaction->{
            Job job=new Job();job.setFormat(finalFormat);job.setStatus(JobStatus.DONE);job.setRequest("Açılan belge: "+fileName);
            job.setFileName(fileName);job.setCompletedAt(OffsetDateTime.now());jobs.saveAndFlush(job);
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
                @Override public void afterCompletion(int status){if(status!=STATUS_COMMITTED) storage.deleteJobFiles(job.getId());}
            });
            Path path=storage.resolveOutputPath(job.getId(),finalFormat);
            try{Files.createDirectories(path.getParent());Files.write(path,bytes,StandardOpenOption.CREATE_NEW);}
            catch(Exception e){throw new IllegalStateException("Belge diske yazılamadı",e);}
            job.setFilePath(path.toString());job.setFileSize((long)bytes.length);jobs.saveAndFlush(job);return job.getId();
        });
    }
    private Job requirePlainTextJob(UUID jobId) {
        Job job=jobs.findById(jobId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Belge bulunamadı"));
        if(!PLAIN_TEXT_FORMATS.contains(job.getFormat()) || job.getStatus()!=JobStatus.DONE || job.getFilePath()==null
            || !job.getExpiresAt().isAfter(OffsetDateTime.now()) || !Files.isRegularFile(Path.of(job.getFilePath())))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Düzenlemeye uygun belge bulunamadı");
        return job;
    }
    public TextDocument readText(UUID jobId) {
        Job job=requirePlainTextJob(jobId);
        try {
            String content=Files.readString(Path.of(job.getFilePath()),java.nio.charset.StandardCharsets.UTF_8);
            return new TextDocument(job.getId(),job.getFormat(),content);
        } catch(Exception e) {throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"Belge okunamadı");}
    }
    public TextDocument saveText(UUID jobId,String content) {
        Job parent=requirePlainTextJob(jobId);
        if(content==null || content.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"İçerik boş olamaz");
        byte[] bytes=content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if(bytes.length>com.officemanipulator.office.OfficeDocumentClient.MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Belge en fazla 100 MB olabilir");
        OutputFormat format=parent.getFormat();
        UUID newId=tx.execute(transaction->{
            Job saved=new Job();saved.setFormat(format);saved.setRequest("Düzenlendi: "+parent.getRequest());
            saved.setParentJobId(canonicalRoot(parent.getId()));saved.setStatus(JobStatus.DONE);saved.setCompletedAt(OffsetDateTime.now());
            saved.setExpiresAt(OffsetDateTime.now().plusHours(72));saved.setFileName(parent.getFileName());
            jobs.saveAndFlush(saved);
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
                @Override public void afterCompletion(int status){if(status!=STATUS_COMMITTED) storage.deleteJobFiles(saved.getId());}
            });
            Path path=storage.resolveOutputPath(saved.getId(),format);
            try {
                Files.createDirectories(path.getParent());Files.write(path,bytes,StandardOpenOption.CREATE_NEW);
                validator.validate(path,format);
            } catch(com.officemanipulator.validator.FileValidator.ValidationException e) {throw new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage());}
            catch(Exception e) {throw new IllegalStateException("Belge diske yazılamadı",e);}
            saved.setFilePath(path.toString());saved.setFileSize((long)bytes.length);jobs.saveAndFlush(saved);
            return saved.getId();
        });
        return new TextDocument(newId,format,content);
    }
    private UUID canonicalRoot(UUID jobId) {
        Job current=jobs.findById(jobId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Belge bulunamadı"));
        OutputFormat format=current.getFormat();
        Set<UUID> seen=new HashSet<>();
        while(current.getParentJobId()!=null) {
            if(!seen.add(current.getId()) || seen.size()>1000) throw new ResponseStatusException(HttpStatus.CONFLICT,"Belge sürüm zinciri geçersiz");
            Job parent=jobs.findById(current.getParentJobId()).orElse(null);
            if(parent==null || parent.getFormat()!=format) break;
            current=parent;
        }
        return current.getId();
    }
    public Map<String,Object> open(UUID jobId) {
        UUID rootId=canonicalRoot(jobId);
        Session s=tx.execute(status->{
            jobs.findForUpdate(rootId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Belge bulunamadı"));
            UUID sourceId=jobId;
            var previous=db.query("SELECT * FROM office_sessions WHERE root_job_id=? ORDER BY created_at DESC LIMIT 1",OfficeService::row,rootId);
            if(!previous.isEmpty()) {
                Session old=previous.getFirst();
                if(!old.closed() && old.expiresAt().isAfter(OffsetDateTime.now())) return old;
                // Opening the original after closing continues its most recent saved revision.
                sourceId=old.savedJobId();
            }
            Job job=availableJob(sourceId);
            UUID id=UUID.randomUUID();
            db.update("INSERT INTO office_sessions(id,root_job_id,source_job_id,saved_job_id,document_key,expires_at) VALUES (?,?,?,?,?,?)",
                id,rootId,job.getId(),job.getId(),"office-"+id,job.getExpiresAt());
            return session(id);
        });
        return config(s);
    }
    private Map<String,Object> config(Session s) {
        Job source=availableJob(s.sourceJobId());
        String docTicket=tokens.ticket(s.id(),"document",s.expiresAt().toInstant());
        String pluginTicket=tokens.ticket(s.id(),"agent",s.expiresAt().toInstant());
        OutputFormat format=source.getFormat();
        Map<String,Object> config=new LinkedHashMap<>();
        config.put("documentType",onlyOfficeDocumentType(format));config.put("type","desktop");config.put("width","100%");config.put("height","100%");
        config.put("document",Map.of("fileType",format.fileExtension(),"key",s.key(),"title",source.getFileName()==null?"Belge."+format.fileExtension():source.getFileName(),
            "url",appInternal+"/api/office/sessions/"+s.id()+"/document?ticket="+docTicket,
            "permissions",Map.of("edit",true,"download",true,"print",true)));
        config.put("editorConfig",Map.of("mode","edit","lang","tr","callbackUrl",appInternal+"/api/office/sessions/"+s.id()+"/callback",
            "user",Map.of("id","local-user","name","Kullanıcı"),"customization",Map.of("forcesave",false)));
        config.put("token",tokens.sign(config));
        return Map.of("sessionId",s.id(),"apiScriptUrl",publicUrl+"/web-apps/apps/api/documents/api.js","config",config,"agentToken",pluginTicket);
    }
    /** The save-revision chain for a session's document, oldest first. */
    public List<Map<String,Object>> versions(UUID sessionId) {
        Session s=session(sessionId);
        List<Job> chain=new ArrayList<>();
        Job current=jobs.findById(canonicalRoot(s.sourceJobId())).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Belge bulunamadı"));
        chain.add(current);
        Set<UUID> seen=new HashSet<>(List.of(current.getId()));
        while(true) {
            var children=jobs.findByParentJobId(current.getId());
            OutputFormat currentFormat=current.getFormat();
            Job next=children.stream().filter(j->j.getFormat()==currentFormat && seen.add(j.getId())).findFirst().orElse(null);
            if(next==null) break;
            chain.add(next); current=next;
        }
        List<Map<String,Object>> result=new ArrayList<>();
        for(int i=0;i<chain.size();i++) {
            Job job=chain.get(i);
            result.add(new LinkedHashMap<>(Map.of(
                "jobId",job.getId(),"index",i+1,
                "savedAt",job.getCompletedAt()==null?"":job.getCompletedAt().toString(),
                "current",job.getId().equals(s.savedJobId()))));
        }
        return result;
    }
    /** Makes an earlier saved revision the new current document, then closes the session so the
     * next page load opens a fresh ONLYOFFICE session (new document key) reflecting it. */
    public void restoreVersion(UUID sessionId,UUID versionJobId) {
        tx.executeWithoutResult(transaction->{
            Session s=session(sessionId,true);
            UUID rootId=canonicalRoot(s.sourceJobId());
            boolean inChain=versions(sessionId).stream().anyMatch(v->versionJobId.equals(v.get("jobId")));
            if(!inChain) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Bu sürüm bu belgeye ait değil");
            Job version=availableJob(versionJobId);
            Job current=availableJob(s.savedJobId());
            byte[] bytes;
            try {bytes=Files.readAllBytes(Path.of(version.getFilePath()));}
            catch(Exception e) {throw new IllegalStateException("Sürüm okunamadı",e);}
            Job restored=new Job();restored.setFormat(current.getFormat());restored.setRequest("Geri yüklendi: sürüm "+versionJobId);
            restored.setParentJobId(current.getId());restored.setStatus(JobStatus.DONE);restored.setCompletedAt(OffsetDateTime.now());
            restored.setExpiresAt(current.getExpiresAt());restored.setFileName(current.getFileName());
            jobs.saveAndFlush(restored);
            Path output=storage.resolveOutputPath(restored.getId(),current.getFormat());
            try {Files.createDirectories(output.getParent());Files.write(output,bytes,StandardOpenOption.CREATE_NEW);}
            catch(Exception e) {throw new IllegalStateException("Belge diske yazılamadı",e);}
            restored.setFilePath(output.toString());restored.setFileSize((long)bytes.length);jobs.saveAndFlush(restored);
            db.update("UPDATE office_sessions SET saved_job_id=?,revision=revision+1,saved_at=now(),content_hash=?,error=NULL,closed=true WHERE id=?",
                restored.getId(),hash(bytes),sessionId);
        });
    }
    public Map<String,Object> status(UUID id) {
        Session s=session(id);Map<String,Object> result=new LinkedHashMap<>();
        result.put("sessionId",id);result.put("savedJobId",s.savedJobId());result.put("revision",s.revision());result.put("savedAt",s.savedAt());result.put("error",s.error());result.put("closed",s.closed());result.put("acknowledgedRequest",s.acknowledgedRequest());
        return result;
    }
    public record DocumentFile(Path path,OutputFormat format) {}
    public DocumentFile document(UUID id,String ticket) {
        tokens.requireTicket(ticket,id,"document");
        Job job=availableJob(session(id).sourceJobId());
        return new DocumentFile(Path.of(job.getFilePath()),job.getFormat());
    }
    public Map<String,Object> save(UUID id) {
        Session s=session(id);
        if(s.closed()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Editör oturumu kapandı; belgeyi yeniden açın");
        UUID requestId=UUID.randomUUID();
        tx.executeWithoutResult(transaction->{
            session(id,true);
            db.update("INSERT INTO office_save_requests(id,session_id) VALUES (?,?)",requestId,id);
            db.update("UPDATE office_sessions SET last_requested=?,error=NULL WHERE id=?",requestId,id);
        });
        int code=docs.forceSave(s.key(),requestId);
        return Map.of("requested",code==0,"unchanged",code==4,"requestId",requestId);
    }
    public void callback(UUID id,String authorization,JsonNode raw) {
        Session current=session(id);
        JsonNode body=tokens.callback(authorization,raw,current.key());
        int code=body.path("status").asInt();
        if(!Set.of(1,2,3,4,6,7).contains(code)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Bilinmeyen editör durumu");
        try {
            tx.executeWithoutResult(transaction->{
                Session s=session(id,true);
                if(s.closed()) return; // Retries or delayed callbacks cannot roll back a closed revision.
                if(code==3) {db.update("UPDATE office_sessions SET error=? WHERE id=?","Editör belgeyi kaydedemedi; yeniden kaydetmeyi deneyin",id);return;}
                if(code==4) {db.update("UPDATE office_sessions SET closed=true WHERE id=?",id);return;}
                if(code!=2 && code!=6 && code!=7) return;
                UUID requestId=null;
                long sequence=0;
                if(code==6 || code==7) {
                    try {requestId=UUID.fromString(body.path("userdata").asText());}
                    catch(RuntimeException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Kaydetme isteği tanınmıyor");}
                    var requests=db.queryForList("SELECT sequence FROM office_save_requests WHERE id=? AND session_id=?",Long.class,requestId,id);
                    if(requests.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Kaydetme isteği oturumla eşleşmiyor");
                    UUID latestRequest=db.queryForObject("SELECT last_requested FROM office_sessions WHERE id=?",UUID.class,id);
                    if(!requestId.equals(latestRequest)) return;
                    sequence=requests.getFirst();
                    if(sequence<=s.acknowledgedSequence()) return;
                }
                if(code==7) {db.update("UPDATE office_sessions SET error=? WHERE id=?","Editör belgeyi kaydedemedi; yeniden kaydetmeyi deneyin",id);return;}
                Job parent=availableJob(s.savedJobId());
                OutputFormat format=parent.getFormat();
                byte[] bytes=docs.download(body.path("url").asText(),format);
                String hash=hash(bytes);
                if(hash.equals(s.hash())) {
                    db.update("UPDATE office_sessions SET error=NULL,closed=?,acknowledged_request=?,acknowledged_sequence=? WHERE id=?",code==2,requestId,sequence,id);return;
                }
                Job saved=new Job();saved.setFormat(format);saved.setRequest("ONLYOFFICE: "+parent.getRequest());
                saved.setParentJobId(parent.getId());saved.setStatus(JobStatus.DONE);saved.setCompletedAt(OffsetDateTime.now());
                saved.setExpiresAt(OffsetDateTime.now().plusHours(72));saved.setFileName(parent.getFileName());
                jobs.saveAndFlush(saved);
                Path output=storage.resolveOutputPath(saved.getId(),format);
                // Register before touching disk: partial writes also roll back cleanly.
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
                    @Override public void afterCompletion(int status){if(status!=STATUS_COMMITTED) storage.deleteJobFiles(saved.getId());}
                });
                try {
                    Files.createDirectories(output.getParent());
                    Files.write(output,bytes,StandardOpenOption.CREATE_NEW);
                } catch(Exception e) {throw new IllegalStateException("Düzenlenen belge diske yazılamadı",e);}
                saved.setFilePath(output.toString());saved.setFileSize((long)bytes.length);jobs.saveAndFlush(saved);
                db.update("UPDATE office_sessions SET saved_job_id=?,revision=revision+1,saved_at=now(),content_hash=?,error=NULL,closed=?,acknowledged_request=?,acknowledged_sequence=? WHERE id=?",saved.getId(),hash,code==2,requestId,sequence,id);
            });
        } catch(RuntimeException e) {
            if(code==2) db.update("UPDATE office_sessions SET error=? WHERE id=?","Belge kaydedilemedi. Editörü kapatmadan yeniden deneyin.",id);
            else {
                try {db.update("UPDATE office_sessions SET error=? WHERE id=? AND last_requested=?","Belge kaydedilemedi. Editörü kapatmadan yeniden deneyin.",id,UUID.fromString(body.path("userdata").asText()));}
                catch(IllegalArgumentException ignored) { /* Invalid requests cannot poison editor state. */ }
            }
            throw e;
        }
    }
    private static String hash(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
}
