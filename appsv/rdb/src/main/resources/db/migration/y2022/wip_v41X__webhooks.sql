

alter table settings3 add column enable_webhooks_c bool;

-- Not compl normalized, fine.
create table webhooks_conf_t (
  site_id_c  int,
  webhook_id_c webhook_id_d,
  owner_id_c  pat_id_d,   -- who may edit this webhook conf. Null = admins only.
  run_as_id_c  pat_id_d,  -- only sends events about things that run_as_id_c can see
  enabled_c  bool,
  send_to_url_c  http_url_d,
  send_event_type_c  event_type_d,  -- don't change, instead, new
  send_format_v_c i16_gz_d,
  crypto_alg_c text,  -- paseto.v2.local, later v4.local
);


create table webhooks_state_t (
  site_id_c  int,
  webhook_id_c  webhook_id_d,
  sent_up_to_when_c  timestamp,
  sent_up_to_subcount_c  i64_gz_d,
  more_to_send_c  bool,
  broken_c  bool,
);


create table webhooks_sent_t (
  site_id_c  int,
  webhook_id_c  webhook_type_d,
  -- about_page_id_c,
  -- about_post_id_c,
  -- about_post_nr_c,
  -- about_pat_id_c,
  -- about_pat2_id_c,
  -- event_bitfield_c,
  sent_type_c  webhook_type_d,
  sent_to_url_c  http_url_d,
  sent_at_c  timestamp,
  sent_by_app_ver_c  text,
  sent_json_c jsonb,
  payload_unencr_c jsonb,
  crypto_alg_c text,
  ok_resp_at_c  timestamp,
  err_at_c  timestamp,
  err_type_c  int,
  err_msg_c text,
);



--  eventId:  webhook_id + type + post_id + when + subcount
--  eventType:   webhook_type_d
--  dataUnencr: {
--  }
--  dataAsPaseto:  {
--    pageId: __
--    postId: __
--    postNr: __
--    postSource: __
--    categoryExtId: __
--    authorExtId: __
--    
-- }



 "dw2_cats_parent_slug__u" UNIQUE, btree (site_id, parent_id, slug) 
   -- enforce lazily, + softw version in name?
