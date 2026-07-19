const ICP_RECORD_URL = "https://beian.miit.gov.cn/#/Integrated/index";

export function IcpRecordLink() {
  return (
    <footer className="py-4 text-center text-xs text-muted-foreground">
      <a
        href={ICP_RECORD_URL}
        target="_blank"
        rel="noreferrer"
        className="transition-colors hover:text-foreground"
      >
        粤ICP备2026094721号-1
      </a>
    </footer>
  );
}
