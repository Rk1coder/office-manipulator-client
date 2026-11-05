package com.officemanipulator.office;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.officemanipulator.claude.AgentOrchestrator;
import com.officemanipulator.config.AiAvailability;
import com.officemanipulator.domain.Job;
import com.officemanipulator.service.JobService;
import com.officemanipulator.service.JobUsage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@ConditionalOnProperty(name="app.office.enabled",havingValue="true")
public class OfficeController {
    private final OfficeService office;
    private final OfficeTokens tokens;
    private final JobService jobs;
    private final AgentOrchestrator ai;
    private final ObjectMapper json;
    private final AiAvailability aiAvailability;
    private final ConversionSourceRegistry conversionSource;
    @org.springframework.beans.factory.annotation.Autowired private com.officemanipulator.config.LlmClientProvider llm;
    public OfficeController(OfficeService office,OfficeTokens tokens,JobService jobs,AgentOrchestrator ai,ObjectMapper json,AiAvailability aiAvailability,ConversionSourceRegistry conversionSource) {
        this.office=office;this.tokens=tokens;this.jobs=jobs;this.ai=ai;this.json=json;this.aiAvailability=aiAvailability;this.conversionSource=conversionSource;
    }
    @PostMapping(value="/api/office/import",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String,UUID> importDocument(@RequestParam("file") org.springframework.web.multipart.MultipartFile file) throws Exception {
        if(file.getSize()>OfficeDocumentClient.MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Belge en fazla 100 MB olabilir");
        return Map.of("jobId",office.importDocument(file.getOriginalFilename(),file.getBytes()));
    }
    @GetMapping("/api/office/capabilities") public Map<String,Object> capabilities(){
        return Map.of("enabled",true,"formats",OfficeService.EDITABLE_FORMATS.stream().map(Enum::name).sorted().toList());
    }
    @PostMapping("/api/office/jobs/{id}/session") public Map<String,Object> open(@PathVariable UUID id){return office.open(id);}
    @GetMapping("/api/office/sessions/{id}") public Map<String,Object> status(@PathVariable UUID id){return office.status(id);}
    @PostMapping("/api/office/sessions/{id}/save") public Map<String,Object> save(@PathVariable UUID id){return office.save(id);}
    @GetMapping("/api/office/sessions/{id}/versions") public List<Map<String,Object>> versions(@PathVariable UUID id){return office.versions(id);}
    @PostMapping("/api/office/sessions/{id}/versions/{versionJobId}/restore") public Map<String,Object> restore(@PathVariable UUID id,@PathVariable UUID versionJobId){
        office.restoreVersion(id,versionJobId);return Map.of("restored",true);
    }
    @GetMapping("/api/office/sessions/{id}/document") public ResponseEntity<FileSystemResource> document(@PathVariable UUID id,@RequestParam String ticket){
        var file=office.document(id,ticket);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.format().contentType()))
            .body(new FileSystemResource(file.path()));
    }
    @GetMapping("/api/office/preview/{jobId}/document") public ResponseEntity<FileSystemResource> previewDocument(@PathVariable UUID jobId,@RequestParam String ticket){
        tokens.requireTicket(ticket,jobId,"preview");
        Job job=office.availableJob(jobId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(job.getFormat().contentType()))
            .body(new FileSystemResource(job.getFilePath()));
    }
    @GetMapping("/api/office/convert-source/{id}") public ResponseEntity<FileSystemResource> convertSource(@PathVariable UUID id,@RequestParam String ticket){
        tokens.requireTicket(ticket,id,"convert-source");
        var path=conversionSource.take(id);
        if(path==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Dönüştürme kaynağı bulunamadı veya süresi doldu");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).body(new FileSystemResource(path));
    }
    @PostMapping("/api/office/sessions/{id}/callback") public Map<String,Integer> callback(@PathVariable UUID id,@RequestHeader(value="Authorization",required=false) String authorization,@RequestBody JsonNode body){
        office.callback(id,authorization,body);return Map.of("error",0);
    }
    // ONLYOFFICE's plugin SDK (plugins.js) always re-fetches "./config.json" relative to the
    // plugin's own URL on its own bootstrap — with NO query string at all — on top of the
    // session/ticket-qualified URL ONLYOFFICE itself already loaded bridge.html with. Both
    // params are therefore optional here; a session-less request just returns the manifest
    // shape (no secrets in it) so the SDK's own init sequence can complete.
    @GetMapping("/api/office/text/{jobId}") public OfficeService.TextDocument readText(@PathVariable UUID jobId){return office.readText(jobId);}
    @PostMapping("/api/office/text/{jobId}") public OfficeService.TextDocument saveText(@PathVariable UUID jobId,@RequestBody Map<String,String> body){
        return office.saveText(jobId,body.get("content"));
    }
    @PostMapping("/api/office/sessions/{id}/agent") public Map<String,Object> agent(@PathVariable UUID id,
        @RequestHeader(value="Authorization",required=false) String authorization,@RequestBody Map<String,String> body) throws Exception {
        com.officemanipulator.llm.LlmSelection override;
        try {override=llm.resolveOverride(body.get("llm_provider"),body.get("llm_api_key"),body.get("llm_model"));}
        catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage());}
        if(!aiAvailability.enabled(override)) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"AI özelliği bu dağıtımda devre dışı");
        tokens.requireTicket(authorization!=null && authorization.startsWith("Bearer ")?authorization.substring(7):null,id,"agent");
        var session=office.session(id);
        if(session.closed()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Editör oturumu kapandı");
        String text=body.get("text"),instruction=body.get("instruction");
        if(text==null || text.isBlank() || text.length()>20000 || instruction==null || instruction.isBlank() || instruction.length()>10000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Metin veya talimat boş ya da fazla uzun");
        Job job=office.availableJob(session.savedJobId());jobs.ensureBudgetAvailable();
        JobUsage before=JobUsage.snapshot(job);
        try {
            JsonNode result=json.readTree(llm.callWith(override,()->ai.runDocumentEdit(job,text,instruction)).editJson());
            if(!result.path("text").isTextual() || result.path("text").asText().isBlank() || result.path("text").asText().length()>40000)
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Agent geçerli bir metin önerisi döndürmedi");
            Map<String,Object> response=new java.util.LinkedHashMap<>();
            response.put("text",result.path("text").asText());
            response.put("formatting",result.has("formatting") && !result.path("formatting").isNull()
                ? json.convertValue(result.path("formatting"), Map.class) : null);
            return response;
        } finally {jobs.recordFragmentUsage(job.getId(),before,job);}
    }
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> statusError(ResponseStatusException e){
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("error",e.getReason()==null?"İşlem tamamlanamadı":e.getReason()));
    }
    @ExceptionHandler(com.officemanipulator.exception.BudgetExceededException.class) ResponseEntity<?> budgetError(Exception e){
        return ResponseEntity.status(402).body(Map.of("error","API bütçe sınırına ulaşıldı"));
    }
    @ExceptionHandler({org.springframework.web.bind.MissingServletRequestParameterException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,org.springframework.http.converter.HttpMessageNotReadableException.class})
    ResponseEntity<?> invalidRequest(Exception e){return ResponseEntity.badRequest().body(Map.of("error","Eksik veya geçersiz istek"));}
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<?> oversized(Exception e){return ResponseEntity.status(413).body(Map.of("error","Belge en fazla 100 MB olabilir"));}
    @ExceptionHandler(Exception.class) ResponseEntity<?> otherError(Exception e){
        org.slf4j.LoggerFactory.getLogger(OfficeController.class).warn("Office operation failed ({})",e.getClass().getSimpleName());
        return ResponseEntity.status(500).body(Map.of("error","İşlem tamamlanamadı; belgeyi kapatmadan yeniden deneyin"));
    }
}
