

select top 100 * from application_status_event
where application_status_code like 'AutoOffer%' 

select * from odds_dw..application_status_event 
where application_id = '10064433'
order by status_datetime_local


select count(1) from odds_dw..application_status_event


use cLO_stage
select top 100 * from dtProcess

use cLO_stage
select count(1) from dtProcessStep

select * from dtProcessStep
where ProcessStepCode like 'AutoOffer%' 

use odds_dw
select * from application_dim 
where application_id = '10064433'

select * from application_event_fact where application_id = '10064433'
select * from broker_dim where broker_key = 29

select * from application_event_fact
where application_id
in 
(
select distinct application_id from application_status_event
where application_status_code like 'AutoOffer%' 
) 
order by application_id desc, fk_event_date_key desc

use o
select * from application_status_dim 
where application_status_key in (2,3)

select * from cLO_stage..dtProcessStep
where 

