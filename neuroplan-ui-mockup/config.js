// 로컬 목업은 서버 없이 동작합니다.
// Kubernetes 배포 이미지에서는 이 파일을 API 활성화 버전으로 교체합니다.
window.NEUROPLAN_API = {
  enabled: false,
  baseUrl: "/api"
};
