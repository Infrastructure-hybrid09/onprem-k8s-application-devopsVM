FROM 192.168.34.21:5000/neuroplan/nginx:1.28-alpine

COPY neuroplan-login-mvp/frontend/nginx.conf /etc/nginx/nginx.conf
COPY neuroplan-ui-mockup/mvp-main.html /usr/share/nginx/html/index.html
COPY neuroplan-ui-mockup/mvp-app.js /usr/share/nginx/html/mvp-app.js
COPY neuroplan-login-mvp/frontend/config.js /usr/share/nginx/html/config.js
COPY neuroplan-ui-mockup/assets/neuroplan-brain-icon.png /usr/share/nginx/html/assets/neuroplan-brain-icon.png

# 8080 비특권 포트와 /tmp 쓰기 경로만 사용하므로 OpenShift 임의 UID와 호환됩니다.
RUN chgrp -R 0 /usr/share/nginx/html \
    && chmod -R g=u /usr/share/nginx/html
ENV HOME=/tmp
USER 1001
EXPOSE 8080
ENTRYPOINT ["nginx", "-g", "daemon off;"]
