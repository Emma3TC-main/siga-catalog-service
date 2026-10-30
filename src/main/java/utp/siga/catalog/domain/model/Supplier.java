package utp.siga.catalog.domain.model;
public record Supplier(java.util.UUID id,String code,String taxId,String businessName,String tradeName,String contactName,String phone,String email,String address,boolean active,long version) {
 public Supplier { text(code,50,"code"); text(taxId,20,"taxId"); text(businessName,200,"businessName"); optional(tradeName,200,"tradeName"); optional(contactName,160,"contactName"); optional(phone,40,"phone"); optional(email,254,"email"); if(email!=null&&!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))throw new SupplierFieldValidationException("email"); optional(address,300,"address"); }
 private static void text(String v,int n,String f){if(v==null||v.codePointCount(0,v.length())>n)throw new SupplierFieldValidationException(f);} private static void optional(String v,int n,String f){if(v!=null&&v.codePointCount(0,v.length())>n)throw new SupplierFieldValidationException(f);}
}
