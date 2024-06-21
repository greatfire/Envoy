# Guides for security hardening

## Restrict upstream host
Currently, we use dynamic `proxy_pass`, which gets its value from the `Url-Orig` header.
If an attacker makes forged requests to controlled servers, then the origin server's IP is leaked.

Two ways to deal with this:
1. Extract path from `Url-Orig`, then append it to a static upstream host.
2. Make sure upstream hosts are whitelisted, see below.

## Limit response headers
Now all headers are passed through the CDN/Nginx backend, this may leak information, such as origin server IP.
You should contact the upstream provider about this, and filter sensitive headers(whitelist/blacklist certain headers).

## Protect backend ip
Use `proxy_bind IP-WITHOUT-INCOMING` when do proxy_pass, and then run `ufw allow in on INTERFACE to IP-WITH-INCOMING port PORT proto tcp`. 
To find out your default route ip, run `ip route get 8.8.8.8`.

## Example Nginx config
Run `Nginx -V` to see if http-lua is enabled first.

Download [net/url.lua](https://raw.githubusercontent.com/liyo/neturl/master/lib/net/url.lua) to `$LUA_PATH`.
(for openresty, you can also use this [resty/url.lua](https://raw.githubusercontent.com/3scale/lua-resty-url/master/src/resty/url.lua))

```
lua_package_path '/usr/local/lib/lua/5.1/?.lua;/usr/share/lua/5.1/*.lua;;';

lua_shared_dict valid_hosts 1m;
lua_shared_dict valid_headers 1m;

init_by_lua_block {
    local valid_hosts = ngx.shared.valid_hosts
    valid_hosts:set("httpbin.org", true)

    local valid_headers= ngx.shared.valid_headers
    valid_headers:set("content-type", true)
    valid_headers:set("whitelist-header", true)

    -- for copy-on-write (COW) optimization
    require 'net.url'
}

server {
    resolver 8.8.8.8 8.8.4.4;
    location /app1 {
        proxy_ssl_server_name on;
        proxy_pass $http_url_orig;
        proxy_buffering off; # disable buffer for stream
        proxy_set_header Host $http_host_orig;
        proxy_hide_header Host-Orig;
        proxy_hide_header Url-Orig;
        proxy_pass_request_headers on;

        rewrite_by_lua_block {
            local valid_hosts = ngx.shared.valid_hosts
            local url = require 'net.url'
            local upstream_host =  url.parse(ngx.var.http_url_orig).host
            if valid_hosts:get(upstream_host) == nil then
                -- ngx.req.set_uri("/error")
                ngx.status = ngx.HTTP_UNAUTHORIZED
                ngx.say("invalid request")
                ngx.log(ngx.ERR, "invalid upstream host:", upstream_host)
                ngx.exit(ngx.HTTP_OK)
            end
        }

        header_filter_by_lua_block {
            local valid_headers = ngx.shared.valid_headers
            local h, err = ngx.resp.get_headers()

             if err == "truncated" then
                 -- one can choose to ignore or reject the current response here
             end

             for k, v in pairs(h) do
                if valid_headers:get(k) == nil then
                    ngx.log(ngx.ERR, "invalid header: ", k)
                    ngx.header[k] = nil
                end
             end
        }
        add_header X-Client-IP "";
    }
}
```

test it with `curl --header 'Url-Orig: https://httpbin.org/response-headers?whitelist-header=whitelisted-resp-header&x-client-ip=1.2.3.4' 127.0.0.1/p -I`
