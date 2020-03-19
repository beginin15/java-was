package webserver;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

import db.DataBase;
import db.SessionDataBase;
import model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import util.HttpRequestUtils;
import util.HttpResponseUtils;
import util.IOUtils;
import util.SessionUtils;


public class RequestHandler extends Thread {
    private static final Logger log = LoggerFactory.getLogger(RequestHandler.class);

    private Socket connection;

    public RequestHandler(Socket connectionSocket) {
        this.connection = connectionSocket;
    }

    public void run() {
        log.debug("New Client Connect! Connected IP : {}, Port : {}", connection.getInetAddress(),
                connection.getPort());

        try (InputStream in = connection.getInputStream(); OutputStream out = connection.getOutputStream()) {
            // TODO 사용자 요청에 대한 처리는 이 곳에 구현하면 된다.
            DataOutputStream dos = new DataOutputStream(out);
            BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));

            // For.Test
            if (DataBase.getSizeOfUsers() == 0) {
                DataBase.addUser(new User("jay", "1234", "김자윤", "jay@gmail.com"));
            }

            String requestLine = br.readLine();

            String url = HttpRequestUtils.getURL(requestLine);

            Map<String, String> header;

            if (url.equals("/")) url = "/index.html";

            if (url.equals("/user/create")) {
                header = parseHeader(br);

//                log.debug("content-Length : {}", header.get("Content-Length"));

                String body = IOUtils.readData(br, Integer.parseInt(header.get("Content-Length")));

                body = HttpRequestUtils.decode(body);

                Map<String, String> userMap = HttpRequestUtils.parseQueryString(body);

                User user = new User(userMap.get("userId"), userMap.get("password"), userMap.get("name"), userMap.get("email"));
                DataBase.addUser(user);

                log.debug("Database User : {}", DataBase.findUserById(userMap.get("userId")));
                response302Header(dos, "/");

            } else if (url.equals("/user/login")) {
                header = parseHeader(br);
                String body = IOUtils.readData(br, Integer.parseInt(header.get("Content-Length")));
                body = HttpRequestUtils.decode(body);
                Map<String, String> userMap = HttpRequestUtils.parseQueryString(body);
                User loginUser = new User(userMap.get("userId"), userMap.get("password"));
                User dbUser = Optional.ofNullable(DataBase.findUserById(userMap.get("userId")))
                        .orElseThrow(() -> new NoSuchElementException("로그인을 시도한 유저가 존재하지 않습니다"));

                if (dbUser.isSameUser(loginUser)) {
                    log.debug("로그인 성송");
                    String sessionId = SessionUtils.createSessionId();
                    SessionDataBase.addSession(sessionId, dbUser);
                    response302HeaderWithCookie(dos, sessionId);
                    return;
                }
                log.debug("로그인 실패");
                HttpResponseUtils.readLoginFailed(dos);

            } else if (url.contains("list.html")) {
                header = parseHeader(br);
                String cookie = header.get("Cookie");
                String sessionId = cookie.split("=")[1];

                if (SessionDataBase.isSessionIdExist(sessionId)) {
                    log.debug("로그인된 유저입니다");
                    List<User> users = new ArrayList<>(DataBase.findAll());
                    HttpResponseUtils.readUserList(dos, users);
                    return;
                }
                log.debug("해당 세션을 찾을 수 없다.");
                HttpResponseUtils.responseRedirect(dos, "/user/login.html");
//                response302Header(dos, "/user/login.html");

            } else if (url.contains(".css")) {
                byte[] body = Files.readAllBytes(new File("./webapp" + url).toPath());
                response200CssHeader(dos, body.length);
                responseBody(dos, body);

            } else {
                byte[] body;

                if (Files.exists(Paths.get(new File("./webapp") + url))) {
                    body = Files.readAllBytes(new File("./webapp" + url).toPath());
                    response200Header(dos, body.length);
                } else {
                    body = "요청하신 페이지가 없습니다".getBytes();
                    response404Header(dos, body.length);
                }
                responseBody(dos, body);
            }
        } catch (IOException | NoSuchElementException e) {
            log.error(e.getMessage());
        }
    }

    private void response200Header(DataOutputStream dos, int lengthOfBodyContent) {
        try {
            dos.writeBytes("HTTP/1.1 200 OK \r\n");
            dos.writeBytes("Content-Type: text/html;charset=utf-8\r\n");
            dos.writeBytes("Content-Length: " + lengthOfBodyContent + "\r\n");
            dos.writeBytes("\r\n");
        } catch (IOException e) {
            log.error(e.getMessage());
        }
    }

    private void response200CssHeader(DataOutputStream dos, int lengthOfBodyContent) {
        try {
            dos.writeBytes("HTTP/1.1 200 OK \r\n");
            dos.writeBytes("Content-Type: text/css;charset=utf-8\r\n");
            dos.writeBytes("Content-Length: " + lengthOfBodyContent + "\r\n");
            dos.writeBytes("\r\n");
        } catch (IOException e) {
            log.error(e.getMessage());
        }
    }


    private void response302Header(DataOutputStream dos, String path) {
        try {
            dos.writeBytes("HTTP/1.1 302 Found \r\n");
            dos.writeBytes("Location: " + path + "\r\n");
            dos.writeBytes("\r\n");
        } catch (IOException e) {
            log.error(e.getMessage());
        }
    }

    private void response302HeaderWithCookie(DataOutputStream dos, String sessionId) {
        try {
            dos.writeBytes("HTTP/1.1 302 Found \r\n");
            dos.writeBytes("Location: /\r\n");
            dos.writeBytes("Set-Cookie: JSESSIONID=" + sessionId + "; Path=/" + "\r\n");
            dos.writeBytes("\r\n");
        } catch (IOException e) {
            log.error(e.getMessage());
        }
    }


    private void response404Header(DataOutputStream dos, int lengthOfBodyContent) {
        try {
            dos.writeBytes("HTTP/1.1 404 Not Found \r\n");
            dos.writeBytes("Content-Type: text/html;charset=utf-8\r\n");
            dos.writeBytes("Content-Length: " + lengthOfBodyContent + "\r\n");
            dos.writeBytes("\r\n");
        } catch (IOException e) {
            log.error(e.getMessage());
        }
    }


    private void responseBody(DataOutputStream dos, byte[] body) {
        try {
            dos.write(body, 0, body.length);
            dos.flush();
        } catch (IOException e) {
            log.error(e.getMessage());
        }
    }

    private Map<String, String> parseHeader(BufferedReader br) throws IOException {
        Map<String, String> header = new HashMap<>();
        String requestLine;

        while (!(requestLine = br.readLine()).equals("")) {
            HttpRequestUtils.Pair pair = HttpRequestUtils.parseHeader(requestLine);
            header.put(pair.getKey(), pair.getValue());
        }
        return header;
    }
}
