# 医养接力站前端镜像：Vite 构建 → Nginx 托管
FROM node:22-alpine AS build
WORKDIR /app
# 国内服务器构建慢时可加：RUN npm config set registry https://registry.npmmirror.com
COPY package.json ./
RUN npm install
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
ENV TZ=Asia/Shanghai
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
